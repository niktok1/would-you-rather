package io.ntole.wyr.core.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.GuestSessionDto
import io.ntole.wyr.core.auth.RecoverRequest
import io.ntole.wyr.core.auth.RecoverySecretDto
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.like.LikeRequest
import io.ntole.wyr.core.like.LikeResultDto
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SkipRequest
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.vote.VoteRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Just enough of the server, behind a [MockEngine], to put session recovery through the real
 * client: it mints guests, rotates refresh tokens, and rejects a feed request, a vote, a skip, a
 * like, a stats read, a submission or a read of the author's submissions from a player it does not
 * know — the state after a dev server restarts with an empty database. It issues recovery secrets,
 * one live per player, and opens a session for whoever presents one (CLAUDE.md §8a, *Recovery*).
 */
internal class FakeServer {
    private val lock = Mutex()
    private val players = mutableSetOf<String>()
    private val liveRefreshTokens = mutableMapOf<String, String>()
    private var rotations = 0
    private var submissionsAnswered = 0

    /** The player each live recovery secret recovers. A player's new secret kills the one before. */
    private val liveSecrets = mutableMapOf<String, String>()
    private var secretsIssued = 0

    /** Who likes each question, by id. A like sets it, as the server's does, and never toggles it. */
    private val likers = mutableMapOf<String, MutableSet<String>>()

    /** When set, every vote is refused with this status and code, whoever sends it. */
    var refuseVotesWith: Pair<HttpStatusCode, ErrorCode>? = null

    var guestsMinted = 0
        private set

    /** When set, every refresh is refused with this status and code, and rotates nothing. */
    var refuseRefreshesWith: Pair<HttpStatusCode, ErrorCode>? = null

    /** How many refreshes arrived, refused or not. */
    var refreshesSent = 0
        private set

    /** The `Authorization` header of every vote, in arrival order. */
    val votesSentAs = mutableListOf<String?>()

    /** The attempt id of every vote, in arrival order. */
    val voteAttempts = mutableListOf<String>()

    /** The `Authorization` header of every feed request, in arrival order. */
    val feedsSentAs = mutableListOf<String?>()

    /** The `Authorization` header of every stats read, in arrival order. */
    val statsSentAs = mutableListOf<String?>()

    /** When set, every skip is refused with this status and code, whoever sends it. */
    var refuseSkipsWith: Pair<HttpStatusCode, ErrorCode>? = null

    /** The `Authorization` header and question id of every skip, in arrival order. */
    val skipsSentAs = mutableListOf<Pair<String?, String>>()

    /** When set, every submission is refused with this status and error, whoever sends it. */
    var refuseSubmissionsWith: Pair<HttpStatusCode, ErrorDto>? = null

    /** The `Authorization` header and body of every submission, in arrival order. */
    val submissionsSentAs = mutableListOf<Pair<String?, SubmitQuestionRequest>>()

    /** The `Authorization` header of every read of the author's submissions, in arrival order. */
    val submissionListsSentAs = mutableListOf<String?>()

    /** When set, every like is refused with this status and code, whoever sends it. */
    var refuseLikesWith: Pair<HttpStatusCode, ErrorCode>? = null

    /**
     * How many of the next likes are set and then have their answer lost, as when a read times out
     * after the server committed: the client sees a failure for a like that was set.
     */
    var likeAnswersToLose = 0

    /** The `Authorization` header and body of every like, in arrival order. */
    val likesSentAs = mutableListOf<Pair<String?, LikeRequest>>()

    /** When true, a mint answers the session alone, as a server from before recovery does. */
    var mintsWithoutSecret = false

    /** The secret of every recovery, in arrival order. */
    val recoveriesSent = mutableListOf<String>()

    /** The `Authorization` header of every recovery, in arrival order. */
    val recoveriesSentAs = mutableListOf<String?>()

    /**
     * When set, every recovery is refused with this status and error code, or with no body at all
     * when the code is null, as a server from before recovery answers a path it does not have.
     */
    var refuseRecoveriesWith: Pair<HttpStatusCode, ErrorCode?>? = null

    /** How many of the next recoveries never get an answer, as when the connection drops first. */
    var recoveriesToLose = 0

    /** How many requests for a new recovery secret arrived, refused or not. */
    var secretRequestsSent = 0
        private set

    /** When set, every request for a new secret is refused like [refuseRecoveriesWith]. */
    var refuseSecretRequestsWith: Pair<HttpStatusCode, ErrorCode?>? = null

    /** How many of the next requests for a new secret never get an answer. */
    var secretRequestsToLose = 0

    val engine = MockEngine { request -> lock.withLock { handle(request) } }

    /** A player the server already has, as another phone left them, with [secret] live when given. */
    fun knowPlayer(
        playerId: String,
        secret: String? = null,
    ) {
        players += playerId
        if (secret != null) liveSecrets[secret] = playerId
    }

    /** Whether [secret] still recovers anybody. */
    fun isLive(secret: String): Boolean = secret in liveSecrets

    /** Every player, token and secret gone, as when the dev server restarts on an empty database. */
    fun forgetEverything() {
        players.clear()
        liveRefreshTokens.clear()
        liveSecrets.clear()
    }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData =
        when (request.url.encodedPath) {
            WyrApi.Paths.AUTH_GUEST -> {
                guestsMinted++
                val playerId = "guest$guestsMinted"
                issueSession(playerId, recoverySecret = if (mintsWithoutSecret) null else issueSecret(playerId))
            }

            WyrApi.Paths.AUTH_RECOVER -> {
                recover(request)
            }

            WyrApi.Paths.MY_RECOVERY_SECRET -> {
                replaceSecret(request)
            }

            WyrApi.Paths.AUTH_REFRESH -> {
                refreshesSent++
                val token = WyrJson.decodeFromString<RefreshRequest>(request.body.toByteArray().decodeToString())
                val refusal = refuseRefreshesWith
                // Rotation: a refresh token works once.
                val player = if (refusal == null) liveRefreshTokens.remove(token.refreshToken) else null
                when {
                    refusal != null -> respondErrorDto(refusal.first, refusal.second)
                    player == null -> respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.INVALID_REFRESH_TOKEN)
                    else -> issueSession(player)
                }
            }

            WyrApi.Paths.QUESTIONS -> {
                if (request.method == HttpMethod.Post) {
                    submit(request)
                } else {
                    val authorization = request.headers[HttpHeaders.Authorization]
                    feedsSentAs += authorization
                    if (authorization?.removePrefix("Bearer access-") in players) {
                        respondJson(WyrJson.encodeToString(BATCH))
                    } else {
                        respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                    }
                }
            }

            WyrApi.Paths.MY_QUESTIONS -> {
                val authorization = request.headers[HttpHeaders.Authorization]
                submissionListsSentAs += authorization
                if (authorization?.removePrefix("Bearer access-") in players) {
                    respondJson(WyrJson.encodeToString(SUBMISSIONS))
                } else {
                    respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                }
            }

            WyrApi.Paths.VOTES -> {
                val authorization = request.headers[HttpHeaders.Authorization]
                votesSentAs += authorization
                voteAttempts +=
                    WyrJson.decodeFromString<VoteRequest>(request.body.toByteArray().decodeToString()).attemptId
                val player = authorization?.removePrefix("Bearer access-")
                val refusal = refuseVotesWith
                when {
                    refusal != null -> respondErrorDto(refusal.first, refusal.second)
                    player !in players -> respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                    else -> respondJson(VOTE_RESULT_JSON)
                }
            }

            WyrApi.Paths.SKIPS -> {
                val authorization = request.headers[HttpHeaders.Authorization]
                val skip = WyrJson.decodeFromString<SkipRequest>(request.body.toByteArray().decodeToString())
                skipsSentAs += authorization to skip.questionId
                val player = authorization?.removePrefix("Bearer access-")
                val refusal = refuseSkipsWith
                when {
                    refusal != null -> respondErrorDto(refusal.first, refusal.second)
                    player !in players -> respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                    else -> respond("", HttpStatusCode.NoContent)
                }
            }

            WyrApi.Paths.LIKES -> {
                like(request)
            }

            WyrApi.Paths.ME -> {
                val authorization = request.headers[HttpHeaders.Authorization]
                statsSentAs += authorization
                val player = authorization?.removePrefix("Bearer access-")
                if (player != null && player in players) {
                    respondJson(WyrJson.encodeToString(STATS.copy(playerId = player)))
                } else {
                    respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                }
            }

            else -> {
                error("FakeServer has no route for ${request.url}")
            }
        }

    /** Opens a session for the player [RecoverRequest.recoverySecret] recovers, and spends nothing. */
    private suspend fun MockRequestHandleScope.recover(request: HttpRequestData): HttpResponseData {
        val body = request.body.toByteArray().decodeToString()
        val secret = WyrJson.decodeFromString<RecoverRequest>(body).recoverySecret
        recoveriesSent += secret
        recoveriesSentAs += request.headers[HttpHeaders.Authorization]
        if (recoveriesToLose > 0) {
            recoveriesToLose--
            throw SocketTimeoutException("read timed out")
        }
        refuseRecoveriesWith?.let { return respondRefusal(it) }
        val player = liveSecrets[secret]
        return if (player == null) {
            respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.INVALID_RECOVERY_SECRET)
        } else {
            issueSession(player)
        }
    }

    /** Gives a known player a new secret, which kills the one before. */
    private fun MockRequestHandleScope.replaceSecret(request: HttpRequestData): HttpResponseData {
        secretRequestsSent++
        if (secretRequestsToLose > 0) {
            secretRequestsToLose--
            throw SocketTimeoutException("read timed out")
        }
        refuseSecretRequestsWith?.let { return respondRefusal(it) }
        val player = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer access-")
        if (player == null || player !in players) {
            return respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
        }
        return respondJson(WyrJson.encodeToString(RecoverySecretDto(issueSecret(player))))
    }

    private fun issueSecret(playerId: String): String {
        liveSecrets.values.removeAll { it == playerId }
        return "secret-$playerId-${++secretsIssued}".also { liveSecrets[it] = playerId }
    }

    private fun MockRequestHandleScope.respondRefusal(refusal: Pair<HttpStatusCode, ErrorCode?>): HttpResponseData {
        val code = refusal.second ?: return respond("", refusal.first)
        return respondErrorDto(refusal.first, code)
    }

    /** Sets a known player's like as asked for, and answers with the question's likes as they then stand. */
    private suspend fun MockRequestHandleScope.like(request: HttpRequestData): HttpResponseData {
        val authorization = request.headers[HttpHeaders.Authorization]
        val like = WyrJson.decodeFromString<LikeRequest>(request.body.toByteArray().decodeToString())
        likesSentAs += authorization to like
        val player = authorization?.removePrefix("Bearer access-")
        val refusal = refuseLikesWith
        if (refusal != null) return respondErrorDto(refusal.first, refusal.second)
        if (player == null || player !in players) {
            return respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
        }

        val holders = likers.getOrPut(like.questionId) { mutableSetOf() }
        if (like.liked) holders += player else holders -= player
        if (likeAnswersToLose > 0) {
            likeAnswersToLose--
            throw SocketTimeoutException("read timed out")
        }
        val likes = LikeResultDto(questionId = like.questionId, likeCount = holders.size, likedByMe = player in holders)
        return respondJson(WyrJson.encodeToString(likes))
    }

    /** Stores nothing: answers a known player's submission as pending, exactly as it was sent. */
    private suspend fun MockRequestHandleScope.submit(request: HttpRequestData): HttpResponseData {
        val authorization = request.headers[HttpHeaders.Authorization]
        val submission = WyrJson.decodeFromString<SubmitQuestionRequest>(request.body.toByteArray().decodeToString())
        submissionsSentAs += authorization to submission
        val refusal = refuseSubmissionsWith
        return when {
            refusal != null -> {
                respond(WyrJson.encodeToString(refusal.second), refusal.first, JSON_HEADERS)
            }

            authorization?.removePrefix("Bearer access-") !in players -> {
                respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
            }

            else -> {
                submissionsAnswered++
                val stored =
                    SubmissionDto(
                        id = "s$submissionsAnswered",
                        optionA = submission.optionA,
                        optionB = submission.optionB,
                        categories = submission.categories,
                        status = QuestionStatus.PENDING,
                        submittedAt = SUBMITTED_AT,
                    )
                respond(WyrJson.encodeToString(stored), HttpStatusCode.Created, JSON_HEADERS)
            }
        }
    }

    /** A session for [playerId], answered as a mint's when it comes with a [recoverySecret]. */
    private fun MockRequestHandleScope.issueSession(
        playerId: String,
        recoverySecret: String? = null,
    ): HttpResponseData {
        players += playerId
        val refreshToken = "refresh-$playerId-${rotations++}"
        liveRefreshTokens[refreshToken] = playerId
        val session =
            SessionDto(
                playerId = playerId,
                accessToken = "access-$playerId",
                refreshToken = refreshToken,
                accessTokenExpiresInSeconds = 900,
            )
        if (recoverySecret == null) return respondJson(WyrJson.encodeToString(session))
        val guest =
            GuestSessionDto(
                playerId = session.playerId,
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                accessTokenExpiresInSeconds = session.accessTokenExpiresInSeconds,
                recoverySecret = recoverySecret,
            )
        return respondJson(WyrJson.encodeToString(guest))
    }

    companion object {
        private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

        /** When the server says it stored every submission, in epoch milliseconds. */
        const val SUBMITTED_AT = 1_790_000_000_000L

        /** What a read of the author's submissions answers every known player, newest first. */
        val SUBMISSIONS =
            SubmissionListDto(
                listOf(
                    SubmissionDto(
                        id = "s2",
                        optionA = "s2-a",
                        optionB = "s2-b",
                        categories = listOf(QuestionCategory.ETHICS),
                        status = QuestionStatus.REJECTED,
                        rejectionReason = "a duplicate",
                        submittedAt = SUBMITTED_AT + 1,
                    ),
                    SubmissionDto(
                        id = "s1",
                        optionA = "s1-a",
                        optionB = "s1-b",
                        categories = listOf(QuestionCategory.FOOD, QuestionCategory.RANDOM),
                        status = QuestionStatus.PENDING,
                        submittedAt = SUBMITTED_AT,
                    ),
                ),
            )

        /** What the feed serves every known player. */
        val BATCH =
            QuestionPageDto(
                questions =
                    listOf(
                        QuestionDto(
                            id = "q1",
                            optionA = "q1-a",
                            optionB = "q1-b",
                            categories = listOf(QuestionCategory.FOOD),
                        ),
                    ),
            )

        /** What a stats read answers every known player, with that player's id in it. */
        val STATS =
            PlayerStatsDto(
                playerId = "",
                totalPoints = 7,
                answersGiven = 9,
                questionsAnswered = 5,
                cycle = 2,
                dueThisCycle = 11,
                likesReceived = 3,
            )
    }
}

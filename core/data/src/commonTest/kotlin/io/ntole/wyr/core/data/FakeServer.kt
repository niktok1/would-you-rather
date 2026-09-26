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
import io.ntole.wyr.core.auth.AccountDto
import io.ntole.wyr.core.auth.LoginRequest
import io.ntole.wyr.core.auth.PlayGamesSignInRequest
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.core.category.CategoryListDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SkipRequest
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.core.reaction.ReactionRequest
import io.ntole.wyr.core.reaction.ReactionResultDto
import io.ntole.wyr.core.vote.VoteRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Just enough of the server, behind a [MockEngine], to put session recovery through the real
 * client: it mints guests, rotates refresh tokens, and rejects a feed request, a vote, a skip, a
 * like, a stats read, a submission, a read of the author's submissions, a registration or a logout
 * from a player it does not know — the state after a dev server restarts with an empty database. It
 * registers a guest as an account and logs one in, a new session of the account's player each time.
 * It lists the categories to anybody, a session or none.
 */
internal class FakeServer {
    private val lock = Mutex()
    private val players = mutableSetOf<String>()
    private val liveRefreshTokens = mutableMapOf<String, String>()
    private var rotations = 0
    private var submissionsAnswered = 0

    /**
     * What each player thinks of each question, by question id and then player. A reaction sets it, as
     * the server's does, and never toggles it.
     */
    private val reactions = mutableMapOf<String, MutableMap<String, Reaction>>()

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

    /** When set, every reaction is refused with this status and code, whoever sends it. */
    var refuseReactionsWith: Pair<HttpStatusCode, ErrorCode>? = null

    /**
     * How many of the next reactions are set and then have their answer lost, as when a read times out
     * after the server committed: the client sees a failure for a reaction that was set.
     */
    var reactionAnswersToLose = 0

    /** The `Authorization` header and body of every reaction, in arrival order. */
    val reactionsSentAs = mutableListOf<Pair<String?, ReactionRequest>>()

    /** Each account's password and player, by its username as kept, lower-cased. */
    val accounts = mutableMapOf<String, Pair<String, String>>()

    /** The `Authorization` header and body of every registration, in arrival order. */
    val registrationsSentAs = mutableListOf<Pair<String?, RegisterRequest>>()

    /** The `Authorization` header and body of every login, in arrival order. */
    val loginsSentAs = mutableListOf<Pair<String?, LoginRequest>>()

    /** The `Authorization` header of every logout, in arrival order. */
    val logoutsSentAs = mutableListOf<String?>()

    /** When set, every logout is refused with this status and code, whoever sends it. */
    var refuseLogoutsWith: Pair<HttpStatusCode, ErrorCode>? = null

    /**
     * The Play Games player each server auth code names, as Google would answer: a code not here is one
     * Google refuses. Each works once, as Google's do.
     */
    val playGamesCodes = mutableMapOf<String, String>()

    /** The player each Play Games player is linked to, by the Play Games player's id. */
    val playGamesLinks = mutableMapOf<String, String>()

    /** The `Authorization` header and code of every Play Games sign-in, in arrival order. */
    val playGamesSentAs = mutableListOf<Pair<String?, String>>()

    /** When set, every Play Games sign-in is refused with this status and code, whoever sends it. */
    var refusePlayGamesWith: Pair<HttpStatusCode, ErrorCode>? = null

    /** What a read of the categories answers, whoever sends it: [CATEGORIES] unless a test says otherwise. */
    var categories: CategoryListDto = CATEGORIES

    /** When set, every read of the categories is refused with this status and code. */
    var refuseCategoriesWith: Pair<HttpStatusCode, ErrorCode>? = null

    /** The `Authorization` header of every read of the categories, in arrival order. */
    val categoriesSentAs = mutableListOf<String?>()

    val engine = MockEngine { request -> lock.withLock { handle(request) } }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData =
        when (request.url.encodedPath) {
            WyrApi.Paths.AUTH_GUEST -> {
                guestsMinted++
                issueSession("guest$guestsMinted")
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

            WyrApi.Paths.REACTIONS -> {
                react(request)
            }

            // To anybody, with or without a session, as the server's does.
            WyrApi.Paths.CATEGORIES -> {
                categoriesSentAs += request.headers[HttpHeaders.Authorization]
                val refusal = refuseCategoriesWith
                if (refusal != null) {
                    respondErrorDto(refusal.first, refusal.second)
                } else {
                    respondJson(WyrJson.encodeToString(categories))
                }
            }

            WyrApi.Paths.AUTH_REGISTER -> {
                register(request)
            }

            WyrApi.Paths.AUTH_LOGIN -> {
                val login = WyrJson.decodeFromString<LoginRequest>(request.body.toByteArray().decodeToString())
                loginsSentAs += request.headers[HttpHeaders.Authorization] to login
                val account = accounts[login.username.lowercase()]
                if (account != null && account.first == login.password) {
                    issueSession(account.second)
                } else {
                    respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.INVALID_LOGIN)
                }
            }

            WyrApi.Paths.AUTH_PLAY_GAMES -> {
                playGames(request)
            }

            WyrApi.Paths.AUTH_LOGOUT -> {
                val authorization = request.headers[HttpHeaders.Authorization]
                logoutsSentAs += authorization
                val player = authorization?.removePrefix("Bearer access-")
                val refusal = refuseLogoutsWith
                when {
                    refusal != null -> respondErrorDto(refusal.first, refusal.second)
                    player !in players -> respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                    else -> respond("", HttpStatusCode.NoContent)
                }
            }

            WyrApi.Paths.ME -> {
                val authorization = request.headers[HttpHeaders.Authorization]
                statsSentAs += authorization
                val player = authorization?.removePrefix("Bearer access-")
                if (player != null && player in players) {
                    val username = accounts.entries.firstOrNull { it.value.second == player }?.key
                    val linked = player in playGamesLinks.values
                    respondJson(
                        WyrJson.encodeToString(
                            STATS.copy(playerId = player, username = username, playGamesLinked = linked),
                        ),
                    )
                } else {
                    respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                }
            }

            else -> {
                error("FakeServer has no route for ${request.url}")
            }
        }

    /**
     * Sets a known player's reaction as asked for, and answers with the question's reactions as they
     * then stand.
     */
    private suspend fun MockRequestHandleScope.react(request: HttpRequestData): HttpResponseData {
        val authorization = request.headers[HttpHeaders.Authorization]
        val asked = WyrJson.decodeFromString<ReactionRequest>(request.body.toByteArray().decodeToString())
        reactionsSentAs += authorization to asked
        val player = authorization?.removePrefix("Bearer access-")
        val refusal = refuseReactionsWith
        if (refusal != null) return respondErrorDto(refusal.first, refusal.second)
        if (player == null || player !in players) {
            return respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
        }

        val held = reactions.getOrPut(asked.questionId) { mutableMapOf() }
        if (asked.reaction == Reaction.NONE) held -= player else held[player] = asked.reaction
        if (reactionAnswersToLose > 0) {
            reactionAnswersToLose--
            throw SocketTimeoutException("read timed out")
        }
        val result =
            ReactionResultDto(
                questionId = asked.questionId,
                likeCount = held.values.count { it == Reaction.LIKE },
                dislikeCount = held.values.count { it == Reaction.DISLIKE },
                myReaction = held[player] ?: Reaction.NONE,
            )
        return respondJson(WyrJson.encodeToString(result))
    }

    /** Registers a known player, a guest, under the name asked for, lower-cased, as the server's does. */
    private suspend fun MockRequestHandleScope.register(request: HttpRequestData): HttpResponseData {
        val authorization = request.headers[HttpHeaders.Authorization]
        val registration = WyrJson.decodeFromString<RegisterRequest>(request.body.toByteArray().decodeToString())
        registrationsSentAs += authorization to registration
        val player = authorization?.removePrefix("Bearer access-")
        val username = registration.username.lowercase()
        return when {
            player == null || player !in players -> {
                respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
            }

            accounts.values.any { it.second == player } -> {
                respondErrorDto(HttpStatusCode.Conflict, ErrorCode.ALREADY_REGISTERED)
            }

            username in accounts -> {
                respondErrorDto(HttpStatusCode.Conflict, ErrorCode.USERNAME_TAKEN)
            }

            else -> {
                accounts[username] = registration.password to player
                respondJson(WyrJson.encodeToString(AccountDto(username)))
            }
        }
    }

    /**
     * A Play Games sign-in, as the server's: a bearer it does not know is 401 before the code is spent;
     * a code Google refuses is 422; a Play Games player linked already signs in as its player, and one
     * linked to nobody is linked to the bearer's player, or to one minted for it. A new session either
     * way.
     */
    private suspend fun MockRequestHandleScope.playGames(request: HttpRequestData): HttpResponseData {
        val authorization = request.headers[HttpHeaders.Authorization]
        val code = WyrJson.decodeFromString<PlayGamesSignInRequest>(request.body.toByteArray().decodeToString())
        playGamesSentAs += authorization to code.serverAuthCode
        val caller = authorization?.removePrefix("Bearer access-")
        val refusal = refusePlayGamesWith
        if (refusal != null) return respondErrorDto(refusal.first, refusal.second)
        if (caller != null && caller !in players) {
            return respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
        }
        val gamesPlayer =
            playGamesCodes.remove(code.serverAuthCode)
                ?: return respondErrorDto(HttpStatusCode.UnprocessableEntity, ErrorCode.PLAY_GAMES_CODE_REFUSED)
        val player =
            playGamesLinks.getOrPut(gamesPlayer) {
                caller?.takeUnless { it in playGamesLinks.values } ?: "games-$gamesPlayer"
            }
        return issueSession(player)
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

    private fun MockRequestHandleScope.issueSession(playerId: String): HttpResponseData {
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
        return respondJson(WyrJson.encodeToString(session))
    }

    companion object {
        private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

        /** When the server says it stored every submission, in epoch milliseconds. */
        const val SUBMITTED_AT = 1_790_000_000_000L

        /** The first categories, in the order of categories, as V6 wrote them. */
        val CATEGORIES =
            CategoryListDto(
                listOf(
                    CategoryDto(id = "FOOD", nameSr = "Храна", nameEn = "Food"),
                    CategoryDto(id = "LIFESTYLE", nameSr = "Начин живота", nameEn = "Lifestyle"),
                    CategoryDto(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics"),
                    CategoryDto(id = "SUPERPOWERS", nameSr = "Супермоћи", nameEn = "Superpowers"),
                    CategoryDto(id = "ABSURD", nameSr = "Апсурдно", nameEn = "Absurd"),
                ),
            )

        /** What a read of the author's submissions answers every known player, newest first. */
        val SUBMISSIONS =
            SubmissionListDto(
                listOf(
                    SubmissionDto(
                        id = "s2",
                        optionA = "s2-a",
                        optionB = "s2-b",
                        categories = listOf("ETHICS"),
                        status = QuestionStatus.REJECTED,
                        rejectionReason = "a duplicate",
                        submittedAt = SUBMITTED_AT + 1,
                    ),
                    SubmissionDto(
                        id = "s1",
                        optionA = "s1-a",
                        optionB = "s1-b",
                        categories = listOf("FOOD", "ABSURD"),
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
                            categories = listOf("FOOD"),
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

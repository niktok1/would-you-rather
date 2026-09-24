package io.ntole.wyr.core.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.core.question.SkipRequest
import io.ntole.wyr.core.vote.VoteRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Just enough of the server, behind a [MockEngine], to put session recovery through the real
 * client: it mints guests, rotates refresh tokens, and rejects a feed request, a vote, a skip or a
 * stats read from a player it does not know — the state after a dev server restarts with an empty
 * database.
 */
internal class FakeServer {
    private val lock = Mutex()
    private val players = mutableSetOf<String>()
    private val liveRefreshTokens = mutableMapOf<String, String>()
    private var rotations = 0

    /** When set, every vote is refused with this status and code, whoever sends it. */
    var refuseVotesWith: Pair<HttpStatusCode, ErrorCode>? = null

    var guestsMinted = 0
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

    val engine = MockEngine { request -> lock.withLock { handle(request) } }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData =
        when (request.url.encodedPath) {
            WyrApi.Paths.AUTH_GUEST -> {
                guestsMinted++
                issueSession("guest$guestsMinted")
            }

            WyrApi.Paths.AUTH_REFRESH -> {
                val token = WyrJson.decodeFromString<RefreshRequest>(request.body.toByteArray().decodeToString())
                // Rotation: a refresh token works once.
                val player = liveRefreshTokens.remove(token.refreshToken)
                if (player == null) {
                    respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.INVALID_REFRESH_TOKEN)
                } else {
                    issueSession(player)
                }
            }

            WyrApi.Paths.QUESTIONS -> {
                val authorization = request.headers[HttpHeaders.Authorization]
                feedsSentAs += authorization
                if (authorization?.removePrefix("Bearer access-") in players) {
                    respondJson(WyrJson.encodeToString(BATCH))
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
            )
    }
}

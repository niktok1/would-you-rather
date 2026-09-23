package io.ntole.wyr.core.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
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
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Just enough of the server, behind a [MockEngine], to put session recovery through the real
 * client: it mints guests, rotates refresh tokens, and rejects a feed request or a vote from a
 * player it does not know — the state after a dev server restarts with an empty database.
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

    /** The `Authorization` header of every feed request, in arrival order. */
    val feedsSentAs = mutableListOf<String?>()

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
                val player = authorization?.removePrefix("Bearer access-")
                val refusal = refuseVotesWith
                when {
                    refusal != null -> respondErrorDto(refusal.first, refusal.second)
                    player !in players -> respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                    else -> respondJson(VOTE_RESULT_JSON)
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
                        QuestionDto(id = "q1", optionA = "q1-a", optionB = "q1-b", category = QuestionCategory.FOOD),
                    ),
            )
    }
}

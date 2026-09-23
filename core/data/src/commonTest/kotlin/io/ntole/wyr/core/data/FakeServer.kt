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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Just enough of the server, behind a [MockEngine], to put session recovery through the real
 * client: it mints guests, rotates refresh tokens, and rejects a vote from a player it does not
 * know — the state after a dev server restarts with an empty database.
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

            WyrApi.Paths.VOTES -> {
                val authorization = request.headers[HttpHeaders.Authorization]
                votesSentAs += authorization
                val player = authorization?.removePrefix("Bearer access-")
                val refusal = refuseVotesWith
                when {
                    refusal != null -> respondErrorDto(refusal.first, refusal.second)
                    player !in players -> respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                    else -> respondJson(VOTE_RESULT)
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

    private companion object {
        // A literal rather than an encoded VoteResultDto: these tests are about sessions, and the
        // scoring fields are free to change underneath them (unknown keys are ignored).
        const val VOTE_RESULT =
            """{"questionId":"q1","yourChoice":"A","tally":{"votesA":1,"votesB":0},""" +
                """"pointsAwarded":1,"totalPoints":1,"streak":0}"""
    }
}

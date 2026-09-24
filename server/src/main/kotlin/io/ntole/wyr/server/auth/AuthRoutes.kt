package io.ntole.wyr.server.auth

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit
import io.ntole.wyr.server.plugins.receiveOrReject

/**
 * Session endpoints. Both are public: [WyrApi.Paths.AUTH_GUEST] has no credential to present
 * yet, and the refresh token is itself the credential for [WyrApi.Paths.AUTH_REFRESH].
 */
fun Route.authRoutes(
    db: Db,
    tokens: TokenService,
    config: ServerConfig,
) {
    // Zero-click sign-in: the server mints the player. Nothing is asked of the person using the
    // app, and because the identity originates here it cannot be forged by a tampered client. Limited
    // per address, as the caller has no session yet: what bounds a script minting guests to farm with.
    rateLimit(RouteLimit.GUESTS) {
        post(WyrApi.Paths.AUTH_GUEST) {
            val refresh = tokens.issueRefreshToken()
            val expiresAt = System.currentTimeMillis() + config.refreshTokenTtlSeconds * 1_000L

            val player =
                db.query {
                    PlayerStore.createGuest(refreshTokenHash = refresh.hash, refreshExpiresAt = expiresAt)
                }

            call.respond(
                SessionDto(
                    playerId = player.id,
                    accessToken = tokens.issueAccessToken(player.id),
                    refreshToken = refresh.value,
                    accessTokenExpiresInSeconds = config.accessTokenTtlSeconds,
                ),
            )
        }
    }

    // Per address too, as the refresh token is the caller's only credential. A refusal rotates
    // nothing, so the token still works once the budget is back.
    rateLimit(RouteLimit.REFRESHES) {
        post(WyrApi.Paths.AUTH_REFRESH) {
            val body = call.receiveOrReject<RefreshRequest>("refresh request")

            if (body.refreshToken.isBlank()) throw ApiFailure.validation("refreshToken is blank")

            val rotated = tokens.issueRefreshToken()
            val expiresAt = System.currentTimeMillis() + config.refreshTokenTtlSeconds * 1_000L

            // Rotate on every use. The token a rotation displaces still works once more within the grace
            // window (CLAUDE.md §8a), so a refresh whose answer was lost can be sent again with it, and
            // then never again: a token replayed any later, or a second time, is refused.
            val player =
                db.query {
                    PlayerStore.rotateRefreshToken(
                        presentedHash = tokens.hash(body.refreshToken),
                        newHash = rotated.hash,
                        expiresAt = expiresAt,
                        graceMillis = config.refreshGraceSeconds * 1_000L,
                    ) ?: throw ApiFailure.invalidRefreshToken()
                }

            call.respond(
                SessionDto(
                    playerId = player.id,
                    accessToken = tokens.issueAccessToken(player.id),
                    refreshToken = rotated.value,
                    accessTokenExpiresInSeconds = config.accessTokenTtlSeconds,
                ),
            )
        }
    }
}

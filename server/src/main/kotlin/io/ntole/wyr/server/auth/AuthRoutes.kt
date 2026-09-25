package io.ntole.wyr.server.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.AccountDto
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit
import io.ntole.wyr.server.plugins.receiveOrReject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Session and account endpoints. [WyrApi.Paths.AUTH_GUEST] and [WyrApi.Paths.AUTH_REFRESH] are public:
 * the first has no credential to present yet, and the refresh token is itself the credential for the
 * second. [WyrApi.Paths.AUTH_REGISTER] needs a session, the guest's it registers, and
 * [WyrApi.Paths.AUTH_LOGOUT] the one it ends.
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

            // The guest and its first session, in one transaction.
            val session = db.query { SessionStore.open(PlayerStore.createGuest().id, refresh.hash, expiresAt) }

            call.respond(tokens.answer(session, refresh, config))
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

            // Rotate on every use, in the session the token belongs to, so no other device of the player
            // is touched (CLAUDE.md §8a, *Sessions*). The token a rotation displaces still works once more,
            // until the next rotation displaces it or, when REFRESH_GRACE_SECONDS sets a bound, that long
            // after this one (the grace), and never past its own expiry. So a refresh whose answer was lost
            // can be sent again with it, however much later, and then never again: a token replayed a
            // second time is refused.
            val session =
                db.query {
                    SessionStore.rotate(
                        presentedHash = tokens.hash(body.refreshToken),
                        newHash = rotated.hash,
                        expiresAt = expiresAt,
                        graceMillis = config.refreshGraceSeconds?.let { seconds -> seconds * 1_000L },
                    ) ?: throw ApiFailure.invalidRefreshToken()
                }

            call.respond(tokens.answer(session, rotated, config))
        }
    }

    // The guest the token names becomes an account, keeping everything it has (CLAUDE.md §8b,
    // *Accounts*). Limited per player: every try the rules take costs a password hash.
    authenticate(JWT_AUTH) {
        rateLimit(RouteLimit.REGISTRATIONS) {
            post(WyrApi.Paths.AUTH_REGISTER) {
                val playerId = call.authenticatedPlayerId()
                val body = call.receiveOrReject<RegisterRequest>("registration")
                val username = checkedUsername(body.username)
                checkPassword(body.password)

                // Costly on purpose, so off the request's thread and before the transaction, which then
                // holds no connection while it runs.
                val passwordHash = withContext(Dispatchers.Default) { Passwords.hash(body.password) }
                db.query { AccountStore.register(playerId, username, passwordHash) }

                call.respond(AccountDto(username))
            }
        }
    }

    // Ends the session the token was issued for, this device's, and no other (CLAUDE.md §8a,
    // *Sessions*). Per player, as it needs a session.
    authenticate(JWT_AUTH) {
        rateLimit(RouteLimit.LOGOUTS) {
            post(WyrApi.Paths.AUTH_LOGOUT) {
                val playerId = call.authenticatedPlayerId()
                val sessionId = call.authenticatedSessionId()

                db.query { SessionStore.close(sessionId, playerId) }

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** What a mint, a refresh and a login answer: [session]'s credentials, its new [refresh] token among them. */
private fun TokenService.answer(
    session: SessionStore.Session,
    refresh: TokenService.Opaque,
    config: ServerConfig,
): SessionDto =
    SessionDto(
        playerId = session.playerId,
        accessToken = issueAccessToken(session.playerId, session.id),
        refreshToken = refresh.value,
        accessTokenExpiresInSeconds = config.accessTokenTtlSeconds,
    )

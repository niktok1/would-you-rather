package io.ntole.wyr.server.auth

import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.PlayGamesSignInRequest
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit
import io.ntole.wyr.server.plugins.receiveOrReject

/**
 * Signing in with Google Play Games Services (CLAUDE.md §8a, *Play Games sign-in*), asking Google
 * through [playGames], or no route at all when that is null: a server without Play Games configured
 * answers 404, as for a path it never had.
 *
 * The bearer token is optional: authentication lets a request without one through with no principal,
 * and refuses one whose token is expired or forged with the usual 401 before the handler runs, so the
 * code is not sent to Google and stays unspent for the refreshed retry. Limited per address, as a login
 * is, before anything else.
 */
fun Route.playGamesRoutes(
    db: Db,
    tokens: TokenService,
    config: ServerConfig,
    playGames: PlayGamesVerifier?,
) {
    if (playGames == null) return

    rateLimit(RouteLimit.PLAY_GAMES) {
        authenticate(JWT_AUTH, optional = true) {
            post(WyrApi.Paths.AUTH_PLAY_GAMES) {
                val callerId = call.principal<JWTPrincipal>()?.payload?.playerId()
                val body = call.receiveOrReject<PlayGamesSignInRequest>("Play Games sign-in")
                val code = checkedServerAuthCode(body.serverAuthCode)

                // Outside the transaction, which then holds no connection while Google answers. From here
                // on the code is spent, so nothing after it refuses the sign-in.
                val subject =
                    when (val answer = playGames.playerOf(code)) {
                        is PlayGamesAnswer.Player -> answer.playerId
                        PlayGamesAnswer.Refused -> throw ApiFailure.playGamesCodeRefused()
                        PlayGamesAnswer.Unavailable -> throw ApiFailure.playGamesUnavailable()
                    }

                val refresh = tokens.issueRefreshToken()
                val expiresAt = System.currentTimeMillis() + config.refreshTokenTtlSeconds * 1_000L
                // The player it signs in as and its new session, this device's own, in one transaction.
                val session =
                    db.query {
                        val playerId = IdentityStore.signIn(IdentityProvider.PLAY_GAMES, subject, callerId)
                        SessionStore.open(playerId, refresh.hash, expiresAt)
                    }

                call.respond(tokens.answer(session, refresh, config))
            }
        }
    }
}

/**
 * [code] as a server auth code worth sending to Google, or [ApiFailure.validation]: not blank, at most
 * [WyrApi.Limits.MAX_SERVER_AUTH_CODE_LENGTH], and visible ASCII only, which every code Play Games gives
 * is. The message never holds the code.
 */
internal fun checkedServerAuthCode(code: String): String {
    if (code.isEmpty()) throw ApiFailure.validation("serverAuthCode is blank")
    if (code.length > WyrApi.Limits.MAX_SERVER_AUTH_CODE_LENGTH) {
        throw ApiFailure.validation("serverAuthCode is over ${WyrApi.Limits.MAX_SERVER_AUTH_CODE_LENGTH} characters")
    }
    if (code.any { it !in '!'..'~' }) throw ApiFailure.validation("serverAuthCode holds a character no code has")
    return code
}

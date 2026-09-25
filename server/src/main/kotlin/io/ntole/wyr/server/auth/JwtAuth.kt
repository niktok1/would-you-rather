package io.ntole.wyr.server.auth

import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.Payload
import io.ktor.http.HttpHeaders
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.http.parsing.ParseException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.ApplicationRequest
import io.ntole.wyr.server.plugins.ApiFailure

/** Name of the JWT authentication provider, shared by its installation and its routes. */
const val JWT_AUTH: String = "auth-jwt"

/**
 * The player the request's access token names. Only meaningful inside `authenticate(JWT_AUTH)`,
 * whose validation already refuses a token without the claim, so the throw is a backstop.
 *
 * A signed token only proves the player existed when it was issued. Whether the player still
 * does is the route's to check, in the same transaction as whatever it does for them.
 */
fun ApplicationCall.authenticatedPlayerId(): String =
    principal<JWTPrincipal>()?.payload?.playerId() ?: throw ApiFailure.unauthorized("token carries no player id")

/**
 * The session the request's access token was issued for (CLAUDE.md §8a, *Sessions*). Only meaningful
 * inside `authenticate(JWT_AUTH)`. A token from a build before tokens named their session carries none
 * and is refused 401, which a client answers by refreshing, and the refreshed token names it.
 */
fun ApplicationCall.authenticatedSessionId(): String =
    principal<JWTPrincipal>()?.payload?.getClaim(TokenService.CLAIM_SESSION_ID)?.asString()
        ?: throw ApiFailure.unauthorized("token names no session")

/**
 * The player the request's bearer token names, verified as `authenticate(JWT_AUTH)` verifies it but
 * for its expiry ([TokenService.expiredTokenVerifier]), or null when it carries none this server signed.
 *
 * For what runs before authentication has. Ktor's rate limiter picks a request's budget before the
 * `authenticate` block reads the token, whichever of the two is nested in the other, so no principal
 * is there yet (`installRateLimits`). A request this answers null for is refused 401 by the block, and
 * so is one whose token has only expired, after spending its player's budget.
 */
fun TokenService.verifiedPlayerId(request: ApplicationRequest): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val parsed =
        try {
            parseAuthorizationHeader(header)
        } catch (malformed: ParseException) {
            return null
        }
    val bearer = parsed as? HttpAuthHeader.Single ?: return null
    // Any case, as the JWT provider takes it.
    if (!bearer.authScheme.equals(AuthScheme.Bearer, ignoreCase = true)) return null
    return try {
        expiredTokenVerifier.verify(bearer.blob).playerId()
    } catch (refused: JWTVerificationException) {
        null
    }
}

/** The player a verified token names, which a token the server issued always does. */
internal fun Payload.playerId(): String? = getClaim(TokenService.CLAIM_PLAYER_ID).asString()

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
 * The player the request's bearer token names, verified as `authenticate(JWT_AUTH)` verifies it, or
 * null when it carries none that would pass there.
 *
 * For what runs before authentication has. Ktor's rate limiter picks a request's budget before the
 * `authenticate` block reads the token, whichever of the two is nested in the other, so no principal
 * is there yet (`installRateLimits`). A request this answers null for is refused 401 by the block.
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
        verifier.verify(bearer.blob).playerId()
    } catch (refused: JWTVerificationException) {
        null
    }
}

/** The player a verified token names, which a token the server issued always does. */
internal fun Payload.playerId(): String? = getClaim(TokenService.CLAIM_PLAYER_ID).asString()

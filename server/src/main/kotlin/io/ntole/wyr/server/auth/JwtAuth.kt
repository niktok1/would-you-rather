package io.ntole.wyr.server.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
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
    principal<JWTPrincipal>()
        ?.payload
        ?.getClaim(TokenService.CLAIM_PLAYER_ID)
        ?.asString()
        ?: throw ApiFailure.unauthorized("token carries no player id")

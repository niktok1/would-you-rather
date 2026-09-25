package io.ntole.wyr.core.auth

import kotlinx.serialization.Serializable

/**
 * Log in to a registered account on this device (CLAUDE.md §8b, *Accounts*), with a POST to
 * [io.ntole.wyr.core.api.WyrApi.Paths.AUTH_LOGIN]: the [username] and [password] a
 * [RegisterRequest] set. The username is trimmed, then compared lower-cased, as it is kept, so
 * neither a space around it nor its case matters; the password is taken exactly as it was registered.
 *
 * [toString] shows no password, so nothing that prints the request can log it.
 */
@Serializable
public data class LoginRequest(
    public val username: String,
    public val password: String,
) {
    public override fun toString(): String = "LoginRequest(username=$username, password=***)"
}

package io.ntole.wyr.core.auth

import kotlinx.serialization.Serializable

/**
 * Register the session player, a guest, as an account (CLAUDE.md §8b, *Accounts*), keeping everything
 * they have, with a POST to [io.ntole.wyr.core.api.WyrApi.Paths.AUTH_REGISTER]. The [username] and
 * [password] are what logging in on another device then takes.
 *
 * The server lower-cases [username] and keeps it so, trimming nothing: lower-cased, it must be
 * [io.ntole.wyr.core.api.WyrApi.Limits.MIN_USERNAME_LENGTH] to
 * [io.ntole.wyr.core.api.WyrApi.Limits.MAX_USERNAME_LENGTH] characters, each `a` to `z`, `0` to `9`
 * or `_`. [password] may hold any characters, taken as they are, and must be
 * [io.ntole.wyr.core.api.WyrApi.Limits.MIN_PASSWORD_LENGTH] to
 * [io.ntole.wyr.core.api.WyrApi.Limits.MAX_PASSWORD_LENGTH] long, counted as Kotlin's `String.length`
 * counts, in UTF-16 code units.
 *
 * [toString] shows no password, so nothing that prints the request can log it.
 */
@Serializable
public data class RegisterRequest(
    public val username: String,
    public val password: String,
) {
    public override fun toString(): String = "RegisterRequest(username=$username, password=***)"
}

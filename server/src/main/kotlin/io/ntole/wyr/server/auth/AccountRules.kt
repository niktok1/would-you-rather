package io.ntole.wyr.server.auth

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.server.plugins.ApiFailure

/**
 * [raw] lower-cased, as an account keeps its username, or [ApiFailure.invalidUsername] for one that
 * breaks a rule of [RegisterRequest] (CLAUDE.md §8b, *Accounts*). Lower-cased before it is judged,
 * and nothing trimmed: a space anywhere is a character the rules refuse.
 */
internal fun checkedUsername(raw: String): String {
    val username = raw.lowercase()
    if (username.length !in USERNAME_LENGTHS) {
        throw ApiFailure.invalidUsername(
            "username is not ${WyrApi.Limits.MIN_USERNAME_LENGTH} to ${WyrApi.Limits.MAX_USERNAME_LENGTH} characters",
        )
    }
    if (!username.all(
            ::isUsernameChar,
        )
    ) {
        throw ApiFailure.invalidUsername("username has a character other than a-z, 0-9 and _")
    }
    return username
}

/**
 * Refuses a password that breaks a rule of [RegisterRequest], with [ApiFailure.invalidPassword]: only
 * its length is judged, and the message never holds it.
 */
internal fun checkPassword(password: String) {
    if (password.length !in PASSWORD_LENGTHS) {
        throw ApiFailure.invalidPassword(
            "password is not ${WyrApi.Limits.MIN_PASSWORD_LENGTH} to ${WyrApi.Limits.MAX_PASSWORD_LENGTH} characters",
        )
    }
}

/**
 * [raw] as [checkedUsername] keeps it, or null for one it refuses: a name no account can have. For a
 * login, which refuses such a name as it refuses any unknown one.
 */
internal fun usernameOrNull(raw: String): String? =
    raw.lowercase().takeIf { username -> username.length in USERNAME_LENGTHS && username.all(::isUsernameChar) }

/** Whether [checkPassword] takes [password]: one that breaks the rules is no account's. */
internal fun isPassword(password: String): Boolean = password.length in PASSWORD_LENGTHS

private fun isUsernameChar(char: Char): Boolean = char in 'a'..'z' || char in '0'..'9' || char == '_'

private val USERNAME_LENGTHS = WyrApi.Limits.MIN_USERNAME_LENGTH..WyrApi.Limits.MAX_USERNAME_LENGTH
private val PASSWORD_LENGTHS = WyrApi.Limits.MIN_PASSWORD_LENGTH..WyrApi.Limits.MAX_PASSWORD_LENGTH

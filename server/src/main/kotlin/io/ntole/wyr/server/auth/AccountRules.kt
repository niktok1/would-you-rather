package io.ntole.wyr.server.auth

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.server.plugins.ApiFailure

/**
 * [raw] as an account keeps its username, lower-cased, or null for one that breaks a rule of
 * [RegisterRequest] (CLAUDE.md §8a, *Accounts*): lower-cased, and nothing trimmed, it must be
 * [WyrApi.Limits.MIN_USERNAME_LENGTH] to [WyrApi.Limits.MAX_USERNAME_LENGTH] of `a` to `z`, `0` to `9`
 * and `_`, so a space anywhere is refused. A login trims the name before asking, and refuses a name
 * this refuses as it refuses any unknown one.
 */
internal fun usernameOrNull(raw: String): String? =
    raw.lowercase().takeIf { username -> username.length in USERNAME_LENGTHS && username.all(::isUsernameChar) }

/** [usernameOrNull], or [ApiFailure.invalidUsername] for a name it refuses. */
internal fun checkedUsername(raw: String): String =
    usernameOrNull(raw) ?: throw ApiFailure.invalidUsername(USERNAME_RULE)

/**
 * Whether [password] keeps the one rule of [RegisterRequest] a password has: its length, counted as
 * `String.length` counts. A password that breaks it is no account's.
 */
internal fun isPassword(password: String): Boolean = password.length in PASSWORD_LENGTHS

/** Refuses a password [isPassword] refuses, with [ApiFailure.invalidPassword], whose message never holds it. */
internal fun checkPassword(password: String) {
    if (!isPassword(password)) throw ApiFailure.invalidPassword(PASSWORD_RULE)
}

private fun isUsernameChar(char: Char): Boolean = char in 'a'..'z' || char in '0'..'9' || char == '_'

private val USERNAME_LENGTHS = WyrApi.Limits.MIN_USERNAME_LENGTH..WyrApi.Limits.MAX_USERNAME_LENGTH
private val PASSWORD_LENGTHS = WyrApi.Limits.MIN_PASSWORD_LENGTH..WyrApi.Limits.MAX_PASSWORD_LENGTH

private val USERNAME_RULE =
    "a username is ${USERNAME_LENGTHS.first} to ${USERNAME_LENGTHS.last} characters of a-z, 0-9 and _"
private val PASSWORD_RULE = "a password is ${PASSWORD_LENGTHS.first} to ${PASSWORD_LENGTHS.last} characters"

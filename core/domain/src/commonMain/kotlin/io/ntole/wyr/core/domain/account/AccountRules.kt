package io.ntole.wyr.core.domain.account

/**
 * The server's rules for a new account's username and password (CLAUDE.md §8a, *Accounts*), so a
 * form can say what is wrong before anything is sent.
 *
 * A username is lower-cased, and nothing trimmed, then must be [MIN_USERNAME_LENGTH] to
 * [MAX_USERNAME_LENGTH] characters, each `a` to `z`, `0` to `9` or `_`: so capitals are fine and a
 * space anywhere is not. A password may hold anything, and must be [MIN_PASSWORD_LENGTH] to
 * [MAX_PASSWORD_LENGTH] long. Both are counted as `String.length` counts, in UTF-16 code units.
 *
 * The numbers are the server's `WyrApi.Limits`, which this module cannot see (CLAUDE.md §3), so
 * `:core:data`'s tests pin each copy to the wire's.
 */
public object AccountRules {
    public const val MIN_USERNAME_LENGTH: Int = 3
    public const val MAX_USERNAME_LENGTH: Int = 20
    public const val MIN_PASSWORD_LENGTH: Int = 6
    public const val MAX_PASSWORD_LENGTH: Int = 128

    /**
     * What is wrong with [username] by the server's rules, or null when an account can have it. A
     * character the rules refuse is named before a length, since typing more would not put it right.
     */
    public fun usernameProblem(username: String): UsernameProblem? {
        val name = username.lowercase()
        return when {
            !name.all(::isUsernameChar) -> UsernameProblem.INVALID_CHARACTER
            name.length < MIN_USERNAME_LENGTH -> UsernameProblem.TOO_SHORT
            name.length > MAX_USERNAME_LENGTH -> UsernameProblem.TOO_LONG
            else -> null
        }
    }

    /** What is wrong with [password] by the server's rules, or null when an account can have it. */
    public fun passwordProblem(password: String): PasswordProblem? =
        when {
            password.length < MIN_PASSWORD_LENGTH -> PasswordProblem.TOO_SHORT
            password.length > MAX_PASSWORD_LENGTH -> PasswordProblem.TOO_LONG
            else -> null
        }

    private fun isUsernameChar(char: Char): Boolean = char in 'a'..'z' || char in '0'..'9' || char == '_'
}

/** What [AccountRules.usernameProblem] finds wrong with a username. */
public enum class UsernameProblem {
    TOO_SHORT,
    TOO_LONG,

    /** A character other than a letter `a` to `z` in either case, a digit or `_`: a space, say. */
    INVALID_CHARACTER,
}

/** What [AccountRules.passwordProblem] finds wrong with a password. */
public enum class PasswordProblem {
    TOO_SHORT,
    TOO_LONG,
}

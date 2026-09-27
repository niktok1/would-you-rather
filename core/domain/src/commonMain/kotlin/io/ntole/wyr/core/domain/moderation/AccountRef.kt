package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.account.AccountRules

/**
 * An account as a moderator names it to delete it on its player's request (CLAUDE.md §8a, *Deleting an
 * account*, *By a moderator*): by its username, or by its id, the player's, which the game shows on its
 * About screen and which a guest or a player signed in with Play Games alone, who has no username,
 * sends instead.
 */
public sealed interface AccountRef {
    /** The account whose username is [username], lower-cased as the server keeps it. */
    public data class Username(
        public val username: String,
    ) : AccountRef

    /** The account whose id is [accountId]. */
    public data class Id(
        public val accountId: String,
    ) : AccountRef

    public companion object {
        /**
         * What [typed] names, trimmed: a username when it is one by [AccountRules], and otherwise an
         * account id, which is never a username (a player id is a UUID, 36 characters with hyphens); or
         * null when nothing is typed.
         */
        public fun of(typed: String): AccountRef? {
            val trimmed = typed.trim()
            if (trimmed.isEmpty()) return null
            return if (AccountRules.usernameProblem(trimmed) == null) Username(trimmed.lowercase()) else Id(trimmed)
        }
    }
}

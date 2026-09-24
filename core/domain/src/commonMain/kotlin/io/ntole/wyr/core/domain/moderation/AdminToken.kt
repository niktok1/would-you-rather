package io.ntole.wyr.core.domain.moderation

import kotlin.jvm.JvmInline

/**
 * The server's admin token, which makes whoever holds it the moderator (CLAUDE.md §8d,
 * *Moderation*): not a role on a player account, and nothing to do with the player's session.
 *
 * A credential, so it is held in memory for as long as the moderator's screen needs it, and nowhere
 * else: never persisted, never logged. [toString] shows none of it, so a token put into a log line
 * or a failed assertion by mistake reads as redacted.
 *
 * Only [of] makes one, so no token goes out that a request header could not carry.
 */
@JvmInline
public value class AdminToken private constructor(
    public val value: String,
) {
    override fun toString(): String = "AdminToken(redacted)"

    public companion object {
        /**
         * [text] trimmed, as a token, or null when it cannot be one: blank, or holding anything but
         * visible ASCII once trimmed. The server refuses to boot with such a token, since no header
         * could carry it (CLAUDE.md §8), so none of them can be the right one. Trimming only undoes a
         * paste: a real token has no whitespace to lose.
         */
        public fun of(text: String): AdminToken? {
            val token = text.trim()
            return if (token.isNotEmpty() && token.all { it in VISIBLE_ASCII }) AdminToken(token) else null
        }

        private val VISIBLE_ASCII = '!'..'~'
    }
}

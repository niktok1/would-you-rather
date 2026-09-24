package io.ntole.wyr.core.domain.moderation

import kotlin.jvm.JvmInline

/**
 * A moderator's short reason for rejecting a submission, which its author sees beside it
 * (CLAUDE.md §8d, *Moderation*), trimmed, as the server stores it.
 *
 * Only [of] makes one, and only of a reason the server's rules accept. The server refuses any other
 * as a malformed request, 400, which reads as [io.ntole.wyr.core.domain.error.DomainError.SERVER]: a
 * client bug, since the moderator's client is to check a reason before it lets them send it.
 */
@JvmInline
public value class RejectionReason private constructor(
    public val value: String,
) {
    public companion object {
        /**
         * The longest a reason may be once trimmed, counted as `String.length` counts, in UTF-16 code
         * units. It is the server's `WyrApi.Limits.MAX_REJECTION_REASON_LENGTH`, which this module
         * cannot see (CLAUDE.md §3), so `:core:data`'s tests pin the two equal.
         */
        public const val MAX_LENGTH: Int = 200

        /**
         * [text] trimmed, as a reason, or null when the server would refuse it: blank, longer than
         * [MAX_LENGTH], or more than one line, which is a control character, U+2028 or U+2029 left
         * anywhere once trimmed. The server's rules exactly, trimming first as it does. One line is
         * provisional (CLAUDE.md §8b).
         */
        public fun of(text: String): RejectionReason? {
            val reason = text.trim()
            val oneLine = reason.none { it.isISOControl() || it.category in LINE_SEPARATORS }
            return if (reason.isNotEmpty() && reason.length <= MAX_LENGTH && oneLine) RejectionReason(reason) else null
        }

        /** The categories of U+2028 and U+2029, the only characters in either. */
        private val LINE_SEPARATORS = setOf(CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR)
    }
}

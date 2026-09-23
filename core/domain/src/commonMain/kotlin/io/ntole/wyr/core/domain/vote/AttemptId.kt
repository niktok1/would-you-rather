package io.ntole.wyr.core.domain.vote

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/**
 * The idempotency key of one answer (CLAUDE.md §8d, retry safety).
 *
 * Made once per player intent, one tap on a side, and passed again unchanged when that same vote is
 * retried: if the first try landed and only its response was lost, the server replays it instead of
 * paying for it twice. A new attempt is always a fresh answer, so a retry must never make one. That
 * is why the caller owns it and not the vote repository, which cannot tell a retry from a new tap.
 *
 * Only [random] makes one. An id derived from what the vote carries would be the same for two taps
 * on one question, and the second would be replayed instead of answering it again.
 */
@JvmInline
public value class AttemptId private constructor(
    public val value: String,
) {
    public companion object {
        public fun random(): AttemptId = AttemptId(Uuid.random().toString())
    }
}

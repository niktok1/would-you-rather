package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * Where a submitted question stands with the moderator (CLAUDE.md §8d).
 *
 * A growable wire enum, under the same forward-compatibility contract as [QuestionCategory]:
 * [UNKNOWN] is what a status added server-side later decodes as on an older client, so every
 * property of this type declares it as its default. Never sent by the server.
 */
@Serializable
public enum class QuestionStatus {
    /** Waiting for a moderator. Served to nobody. */
    PENDING,

    /** Approved by a moderator: due for every player, its author included, from their current cycle on. */
    APPROVED,

    /** Refused by a moderator, with a short reason ([SubmissionDto.rejectionReason]). Served to nobody. */
    REJECTED,

    /**
     * Approved, then retired by a moderator (CLAUDE.md §8d, *Moderation*): served to nobody until a
     * moderator restores it, when it is [APPROVED] again. Nothing it earned is taken back: its answers'
     * points stay, and its likes stay held and paid. A seed can be retired too.
     */
    RETIRED,

    UNKNOWN,
}

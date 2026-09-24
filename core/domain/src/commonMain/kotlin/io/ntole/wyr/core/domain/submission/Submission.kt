package io.ntole.wyr.core.domain.submission

import io.ntole.wyr.core.domain.question.Category
import kotlin.time.Instant

/**
 * One of the session player's own submitted questions, as its author sees it (CLAUDE.md §8d).
 *
 * Deliberately not the same type as `SubmissionDto`: domain code must never see a DTO, and the
 * mapping between the two lives in `:core:data` (CLAUDE.md §3).
 *
 * [id] is the question's id, the one the feed serves it under once a moderator approves it.
 * [optionA] and [optionB] are as the server stored them, trimmed. [categories] are the ones it is
 * filed under, which a moderator may change when approving it, held as a question holds its own:
 * never empty, and each one this build cannot name is [Category.OTHER].
 *
 * [rejectionReason] is the moderator's short reason, which the server sends only for a
 * [SubmissionStatus.REJECTED] submission. [submittedAt] is when the server stored it.
 */
public data class Submission(
    public val id: String,
    public val optionA: String,
    public val optionB: String,
    public val categories: Set<Category>,
    public val status: SubmissionStatus,
    public val rejectionReason: String?,
    public val submittedAt: Instant,
) {
    init {
        require(categories.isNotEmpty()) { "submission $id is filed under no category" }
    }
}

/**
 * Where a submission stands with the moderator (CLAUDE.md §8d).
 *
 * [OTHER] is where a status this build does not recognise lands: one added server-side later
 * arrives as the wire's `UNKNOWN` and maps here, so the list still loads. It says only that this
 * build cannot tell, so nothing may read it as any of the others: not as [PENDING], still waiting,
 * nor as [APPROVED], served, nor as [REJECTED], refused, nor as [RETIRED], withdrawn. A build from
 * before [RETIRED] lists a retired question as [OTHER].
 */
public enum class SubmissionStatus {
    /** Waiting for a moderator. Served to nobody. */
    PENDING,

    /** Approved by a moderator: served to every player, its author included. */
    APPROVED,

    /** Refused by a moderator, with a short reason ([Submission.rejectionReason]). Served to nobody. */
    REJECTED,

    /**
     * Approved, then retired by a moderator: served to nobody until a moderator restores it, when it is
     * [APPROVED] again. What it earned stays: its likes are still held, and still pay its author.
     */
    RETIRED,

    OTHER,
}

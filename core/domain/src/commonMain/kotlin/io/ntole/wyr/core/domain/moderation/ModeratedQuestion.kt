package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Tally
import kotlin.time.Instant

/**
 * A question as the moderator sees it in the list of every question (CLAUDE.md §8d, *Moderation*):
 * a seed or a player's submission, whatever its status.
 *
 * Deliberately not the same type as `AdminQuestionDto`: domain code must never see a DTO, and the
 * mapping between the two lives in `:core:data` (CLAUDE.md §3).
 *
 * [categories] are held as a question holds its own: never empty, and each one this build cannot name
 * is [Category.OTHER]. [status] is where it stands, [SubmissionStatus.RETIRED] once a moderator
 * retired it, and [SubmissionStatus.OTHER] for a status this build cannot name. [isSeed] is true for
 * one of the server's starter questions, which nobody wrote and which is approved from the start. No
 * author is known: the server sends none.
 *
 * [submittedAt] is when the server stored it, [reviewedAt] when a moderator approved or rejected it
 * (null for a seed and while pending), and [retiredAt] when a moderator retired it, null unless it is
 * retired. [rejectionReason] is the moderator's reason, only for a rejected one. [tally] and
 * [likeCount] are every player's latest answers to it and how many players like it, one moment's
 * numbers, which a retired question keeps.
 */
public data class ModeratedQuestion(
    public val id: String,
    public val optionA: String,
    public val optionB: String,
    public val categories: Set<Category>,
    public val status: SubmissionStatus,
    public val isSeed: Boolean,
    public val submittedAt: Instant,
    public val reviewedAt: Instant?,
    public val retiredAt: Instant?,
    public val rejectionReason: String?,
    public val tally: Tally,
    public val likeCount: Int,
) {
    init {
        require(categories.isNotEmpty()) { "question $id is filed under no category" }
    }
}

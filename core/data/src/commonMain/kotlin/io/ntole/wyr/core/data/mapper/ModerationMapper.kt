package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.RejectSubmissionRequest

/**
 * Domain to wire, for a moderator's approval of [questionId] (CLAUDE.md §8d, *Moderation*): filed
 * under [categories] by their wire names in declaration order, so one selection is always one
 * request, and none names none, which keeps the author's categories. The queue and every decision
 * are answered with `SubmissionDto`s, which map as the author's own list does (`SubmissionMapper`).
 *
 * @throws IllegalArgumentException when [categories] holds [Category.OTHER], which has no wire
 *   category to file a question under.
 */
internal fun approveSubmissionRequest(
    questionId: String,
    categories: Set<Category>,
): ApproveSubmissionRequest =
    ApproveSubmissionRequest(
        questionId = questionId,
        categories =
            categories.sorted().map { category ->
                requireNotNull(category.toWireOrNull()) { "no question can be filed under $category" }
            },
    )

/** A rejection of [questionId], with [reason] as it was checked: trimmed, as the server stores it. */
internal fun rejectSubmissionRequest(
    questionId: String,
    reason: RejectionReason,
): RejectSubmissionRequest = RejectSubmissionRequest(questionId = questionId, reason = reason.value)

package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import kotlin.time.Instant

/**
 * Translation for a player's own submissions: DTO to domain for what the server stored, and domain to
 * wire for submitting one. The only place a `SubmissionDto` and a `Submission` are both in scope
 * (CLAUDE.md §3).
 */
internal fun SubmissionDto.toDomain(): Submission =
    Submission(
        id = id,
        optionA = optionA,
        optionB = optionB,
        categories = categories.toDomainCategories(),
        status = status.toDomain(),
        rejectionReason = rejectionReason,
        submittedAt = Instant.fromEpochMilliseconds(submittedAt),
    )

internal fun QuestionStatus.toDomain(): SubmissionStatus =
    when (this) {
        QuestionStatus.PENDING -> SubmissionStatus.PENDING

        QuestionStatus.APPROVED -> SubmissionStatus.APPROVED

        QuestionStatus.REJECTED -> SubmissionStatus.REJECTED

        // The forward-compatibility landing zone: a status this build predates arrives as UNKNOWN
        // (coerceInputValues, CLAUDE.md §5) and lists as one this build cannot name, never as one of
        // the three it can.
        QuestionStatus.UNKNOWN -> SubmissionStatus.OTHER
    }

/**
 * Domain to wire, for a submission: the options as given, since every rule about them is the
 * server's, and [categories] by their wire names in declaration order, so one selection is always
 * one request.
 *
 * @throws IllegalArgumentException when [categories] is empty, which the server refuses as a
 *   malformed request, or holds [Category.OTHER], which has no wire category to file a question
 *   under.
 */
internal fun submitQuestionRequest(
    optionA: String,
    optionB: String,
    categories: Set<Category>,
): SubmitQuestionRequest {
    require(categories.isNotEmpty()) { "a question is submitted under at least one category" }
    return SubmitQuestionRequest(
        optionA = optionA,
        optionB = optionB,
        categories =
            categories.sorted().map { category ->
                requireNotNull(category.toWireOrNull()) { "no question can be submitted under $category" }
            },
    )
}

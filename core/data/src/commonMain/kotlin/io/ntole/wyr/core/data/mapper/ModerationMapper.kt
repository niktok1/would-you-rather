package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.ModeratedQuestionPage
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.AdminQuestionPageDto
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import kotlin.time.Instant

/**
 * Domain to wire, for a moderator's approval of [questionId] (CLAUDE.md §8d, *Moderation*): filed
 * under [categories], ids, in id order, as a submission's are (`submitQuestionRequest`), and none
 * names none, which keeps the author's categories. The queue and every decision are answered with
 * `SubmissionDto`s, which map as the author's own list does (`SubmissionMapper`).
 */
internal fun approveSubmissionRequest(
    questionId: String,
    categories: Set<String>,
): ApproveSubmissionRequest = ApproveSubmissionRequest(questionId = questionId, categories = categories.sorted())

/** A rejection of [questionId], with [reason] as it was checked: trimmed, as the server stores it. */
internal fun rejectSubmissionRequest(
    questionId: String,
    reason: RejectionReason,
): RejectSubmissionRequest = RejectSubmissionRequest(questionId = questionId, reason = reason.value)

/**
 * A question in the moderator's list, DTO to domain: its categories as a question's are
 * (`toDomainCategories`), its status as a submission's is, `RETIRED` included and one this build
 * cannot name as `OTHER` (`SubmissionMapper`), and its times as instants.
 */
internal fun AdminQuestionDto.toDomain(): ModeratedQuestion =
    ModeratedQuestion(
        id = id,
        optionA = optionA,
        optionB = optionB,
        categories = categories.toDomainCategories(),
        status = status.toDomain(),
        isSeed = seed,
        submittedAt = Instant.fromEpochMilliseconds(submittedAt),
        reviewedAt = reviewedAt?.let(Instant::fromEpochMilliseconds),
        retiredAt = retiredAt?.let(Instant::fromEpochMilliseconds),
        rejectionReason = rejectionReason,
        tally = Tally(votesA = tally.votesA, votesB = tally.votesB),
        likeCount = likeCount,
        dislikeCount = dislikeCount,
    )

internal fun AdminQuestionPageDto.toDomain(): ModeratedQuestionPage =
    ModeratedQuestionPage(questions = questions.map { it.toDomain() }, next = nextCursor?.let(::QuestionCursor))

/**
 * The statuses [QuestionFilter.statuses] asks for, by their wire names in declaration order, so one
 * filter is always one request.
 *
 * @throws IllegalArgumentException for [SubmissionStatus.OTHER], which has no wire status to ask by.
 */
internal fun QuestionFilter.wireStatuses(): List<QuestionStatus> =
    statuses.sorted().map { status ->
        when (status) {
            SubmissionStatus.PENDING -> QuestionStatus.PENDING
            SubmissionStatus.APPROVED -> QuestionStatus.APPROVED
            SubmissionStatus.REJECTED -> QuestionStatus.REJECTED
            SubmissionStatus.RETIRED -> QuestionStatus.RETIRED
            SubmissionStatus.OTHER -> throw IllegalArgumentException("no question can be listed as $status")
        }
    }

/** The categories [QuestionFilter.categories] asks for, ids, in id order, so one filter is always one request. */
internal fun QuestionFilter.wireCategories(): List<String> = categories.sorted()

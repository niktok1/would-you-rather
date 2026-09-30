package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.author.AuthorBlockDto
import io.ntole.wyr.core.author.BlockAuthorRequest
import io.ntole.wyr.core.domain.moderation.AuthorBlock
import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.ModeratedQuestionPage
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.moderation.ReportedQuestion
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.AdminQuestionPageDto
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.report.AdminReportDto
import io.ntole.wyr.core.report.ReportReasonCountDto
import kotlin.time.Instant
import io.ntole.wyr.core.domain.moderation.ReportReason as DomainReportReason
import io.ntole.wyr.core.report.ReportReason as WireReportReason

/**
 * Domain to wire, for a moderator's approval of [questionId] (CLAUDE.md §8d, *Moderation*): filed
 * under [categories], ids, in id order, as a submission's are (`submitQuestionRequest`), and none
 * names none, which keeps the author's categories. The queue and every decision are answered with
 * `SubmissionDto`s, which map as the author's own list does ([toModeratorsSubmission]).
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
 * A submission as the moderator's queue and decisions send it, DTO to domain: as the author's own list
 * maps it (`SubmissionMapper`), and with its author's opaque id, which only the admin routes carry and
 * only the moderator is given.
 */
internal fun SubmissionDto.toModeratorsSubmission(): Submission = toDomain().copy(authorId = authorId)

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
        authorId = authorId,
        categorySuggestion = categorySuggestion,
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

/**
 * A reported question, DTO to domain: its question as the list of every question maps it, and its
 * reasons counted most given first. Reasons this build cannot name all land in
 * [DomainReportReason.UNKNOWN], their counts added, so the counts still add up to the report count.
 */
internal fun AdminReportDto.toDomain(): ReportedQuestion =
    ReportedQuestion(
        question = question.toDomain(),
        reportCount = reportCount,
        reasons = reasons.toDomainReasons(),
        lastReportedAt = Instant.fromEpochMilliseconds(lastReportedAt),
    )

private fun List<ReportReasonCountDto>.toDomainReasons(): Map<DomainReportReason, Int> =
    groupingBy { it.reason.toDomain() }
        .fold(0) { total, counted -> total + counted.count }
        .entries
        .sortedByDescending { it.value }
        .associate { it.key to it.value }

internal fun WireReportReason.toDomain(): DomainReportReason =
    when (this) {
        WireReportReason.OFFENSIVE -> DomainReportReason.OFFENSIVE

        WireReportReason.REAL_PERSON -> DomainReportReason.REAL_PERSON

        WireReportReason.SPAM -> DomainReportReason.SPAM

        WireReportReason.NOT_A_CHOICE -> DomainReportReason.NOT_A_CHOICE

        WireReportReason.OTHER -> DomainReportReason.OTHER

        // A reason this build predates arrives as UNKNOWN (coerceInputValues, CLAUDE.md §5).
        WireReportReason.UNKNOWN -> DomainReportReason.UNKNOWN
    }

/** A block of the author [authorId], with [reason] as it was checked: trimmed, as the server stores it. */
internal fun blockAuthorRequest(
    authorId: String,
    reason: RejectionReason,
): BlockAuthorRequest = BlockAuthorRequest(authorId = authorId, reason = reason.value)

internal fun AuthorBlockDto.toDomain(): AuthorBlock =
    AuthorBlock(authorId = authorId, isBlocked = blocked, rejectedSubmissions = rejectedSubmissions)

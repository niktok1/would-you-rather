package io.ntole.wyr.server.moderation

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.requireValidId
import io.ntole.wyr.server.question.LINE_SEPARATORS

/**
 * [request] as a decision uses it, its categories each once in declaration order, or an
 * [ApiFailure.validation] for what no correct client sends (CLAUDE.md §8d, *Moderation*): an id that
 * is blank or holds a control character, or a category that is not a real one.
 * [QuestionCategory.UNKNOWN] is the decoding fallback for a name the server does not know, never
 * stored, as for a submission. None is not refused: it keeps the author's categories.
 */
internal fun checkedApproval(request: ApproveSubmissionRequest): ApproveSubmissionRequest {
    requireValidId("questionId", request.questionId)
    if (QuestionCategory.UNKNOWN in request.categories) throw ApiFailure.validation("a category is not a real one")

    return request.copy(categories = request.categories.distinct().sorted())
}

/**
 * [request] with its reason trimmed, as it is stored, or an [ApiFailure.validation] for one that breaks
 * a rule of [RejectSubmissionRequest]: an id as for an approval, or a reason that is blank, longer than
 * [WyrApi.Limits.MAX_REJECTION_REASON_LENGTH] or more than one line. All of them are 400, where a
 * submitted option is 422: the moderator's client checks a reason before it lets them send it, so
 * only a bug sends one the rules refuse.
 *
 * One line as an option is one line (`checkedSubmission`): trimming takes whitespace off the ends,
 * and then no control character, nor U+2028 or U+2029, may be left anywhere. PostgreSQL refuses a
 * NUL in text, so without this one would fail as a 500.
 */
internal fun checkedRejection(request: RejectSubmissionRequest): RejectSubmissionRequest {
    requireValidId("questionId", request.questionId)

    val reason = request.reason.trim()
    if (reason.isEmpty()) throw ApiFailure.validation("reason is blank")
    if (reason.length > WyrApi.Limits.MAX_REJECTION_REASON_LENGTH) {
        throw ApiFailure.validation("reason is over ${WyrApi.Limits.MAX_REJECTION_REASON_LENGTH} characters")
    }
    if (reason.any(Char::isISOControl)) throw ApiFailure.validation("reason has a control character")
    if (reason.any { it.category in LINE_SEPARATORS }) throw ApiFailure.validation("reason has a line separator")

    return request.copy(reason = reason)
}

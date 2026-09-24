package io.ntole.wyr.server.question

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.server.plugins.ApiFailure

/**
 * [request] as it is stored, both options trimmed and its categories each once in declaration
 * order, or an [ApiFailure] for one that breaks a rule of [SubmitQuestionRequest] (CLAUDE.md §8d).
 *
 * Two kinds of refusal, so the client can tell the player apart from a bug. What a player can get
 * wrong by typing is [ApiFailure.invalidSubmission]: an option blank, too long or holding a control
 * character or a line separator, or the two options the same ignoring case. No category, or one
 * that is not a real one, is [ApiFailure.validation], as a malformed body is: a picker sends none
 * only by a bug, and [QuestionCategory.UNKNOWN] is the client's decoding fallback, never stored (as
 * for the feed's filter), which no picker offers. A category named twice is filed once, not
 * refused. A request with both kinds of fault is malformed first.
 *
 * Control characters are all refused, not only the NUL PostgreSQL rejects, because an option is one
 * line of text: a newline or tab inside one is pasted by accident, not meant. So are the two line
 * breaks that are not control characters, U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR
 * ([LINE_SEPARATORS]). Trimming comes first, and removes only whitespace: those two and the
 * whitespace controls (tab, newline, VT, FF, CR and 0x1C-0x1F) go at either end like spaces, and any
 * other control character, NUL and DEL included, is refused wherever it is. Nothing else is judged
 * here, invisible characters included: what a question says is the moderator's to accept or reject.
 */
internal fun checkedSubmission(request: SubmitQuestionRequest): SubmitQuestionRequest {
    if (request.categories.isEmpty()) throw ApiFailure.validation("no category")
    if (QuestionCategory.UNKNOWN in request.categories) throw ApiFailure.validation("a category is not a real one")

    val optionA = checkedOption("optionA", request.optionA)
    val optionB = checkedOption("optionB", request.optionB)
    if (optionA.equals(optionB, ignoreCase = true)) throw ApiFailure.invalidSubmission("the two options are the same")

    return SubmitQuestionRequest(
        optionA = optionA,
        optionB = optionB,
        categories = request.categories.distinct().sorted(),
    )
}

/** [raw] trimmed, and measured only then, so padding never counts towards the limit. */
private fun checkedOption(
    field: String,
    raw: String,
): String {
    val option = raw.trim()
    if (option.isEmpty()) throw ApiFailure.invalidSubmission("$field is blank")
    if (option.length > WyrApi.Limits.MAX_OPTION_LENGTH) {
        throw ApiFailure.invalidSubmission("$field is over ${WyrApi.Limits.MAX_OPTION_LENGTH} characters")
    }
    if (option.any(Char::isISOControl)) throw ApiFailure.invalidSubmission("$field has a control character")
    if (option.any { it.category in LINE_SEPARATORS }) throw ApiFailure.invalidSubmission("$field has a line separator")
    return option
}

/** The categories of U+2028 and U+2029, the only characters in either. */
private val LINE_SEPARATORS = setOf(CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR)

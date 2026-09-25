package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * Approve a pending submission (CLAUDE.md §8d, *Moderation*), with a POST to
 * [io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_APPROVALS].
 *
 * [questionId] is the submission's [SubmissionDto.id]. It must not be blank or hold a control
 * character, as for a [SkipRequest].
 *
 * [categories], when there are any, replace the ones the author picked: the question is then filed
 * under exactly those, each once, in the server's order of categories, however often the request
 * names one. None, which a missing list also reads as, keeps the author's. So an approved question
 * is always filed under one at least: there is no asking for none. Each must be the id of a category
 * the server has, as in a [SubmitQuestionRequest]; any other is refused as a malformed request.
 */
@Serializable
public data class ApproveSubmissionRequest(
    public val questionId: String,
    public val categories: List<String> = emptyList(),
)

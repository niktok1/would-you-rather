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
 * under exactly those, each once, in [QuestionCategory] declaration order, however often the request
 * names one. None, which a missing list also reads as, keeps the author's. So an approved question
 * is always filed under one at least: there is no asking for none. Each must be a real one, as in a
 * [SubmitQuestionRequest], and [QuestionCategory.UNKNOWN], which a name the server does not know
 * decodes as ([QuestionCategoryListSerializer]), is refused as a malformed request. The serializer
 * and the empty default are there for the wire enum rule (CLAUDE.md §5), not as values to send.
 */
@Serializable
public data class ApproveSubmissionRequest(
    public val questionId: String,
    @Serializable(with = QuestionCategoryListSerializer::class)
    public val categories: List<QuestionCategory> = emptyList(),
)

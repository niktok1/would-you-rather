package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * One of the requesting player's own submitted questions, as its author sees it (CLAUDE.md §8d):
 * what a submission is answered with, and what [io.ntole.wyr.core.api.WyrApi.Paths.MY_QUESTIONS]
 * lists. A moderator sees a submission the same way: the moderator's queue
 * ([io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_SUBMISSIONS]) lists them, and a decision is answered
 * with one. It names no author either way.
 *
 * [id] is the question's id, the one the feed serves it under once it is approved. [optionA] and
 * [optionB] are as stored, trimmed. [categories] are the ones the question is filed under, which a
 * moderator may change when approving it, sent and decoded as [QuestionDto.categories] are.
 *
 * [categories] must keep its default, and [status] its default, for a value added server-side to
 * decode on an older client — see [QuestionDto.categories] and [QuestionStatus].
 *
 * [rejectionReason] is the moderator's short reason, sent only for a [QuestionStatus.REJECTED]
 * submission and null for any other. [submittedAt] is when the server stored the submission, in
 * epoch milliseconds.
 */
@Serializable
public data class SubmissionDto(
    public val id: String,
    public val optionA: String,
    public val optionB: String,
    public val categories: List<String> = emptyList(),
    public val status: QuestionStatus = QuestionStatus.UNKNOWN,
    public val rejectionReason: String? = null,
    public val submittedAt: Long,
)

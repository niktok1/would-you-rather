package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * One of the requesting player's own submitted questions, as its author sees it (CLAUDE.md §8d):
 * what a submission is answered with, and what [io.ntole.wyr.core.api.WyrApi.Paths.MY_QUESTIONS]
 * lists. A moderator sees a submission the same way: the moderator's queue
 * ([io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_SUBMISSIONS]) lists them, and a decision is answered
 * with one.
 *
 * [authorId] is the author's player id, an opaque name for who wrote it (CLAUDE.md §8d, *Moderation*),
 * sent only on the admin routes, so a moderator can block an author, and null everywhere else: the
 * author's own list and a submission's answer never carry it, nor does anything a player is sent.
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
 *
 * [likeCount] is how many players like the question, [dislikeCount] how many dislike it, and
 * [answerCount] how many players have answered it, each counted once however often they answered
 * (CLAUDE.md §8d, *The Account screen*). The server reads them with the question, in one statement,
 * so they are one moment's numbers. [votesA] and [votesB] are how many of those players picked
 * [optionA] and [optionB], each by their latest pick, so the two add up to [answerCount] (CLAUDE.md §8d,
 * *Question details*). A question never served, pending or rejected, has none; a retired one keeps
 * what it had. They default to none, so a server from before the two sides reads as none on either.
 *
 * [categorySuggestion] is the category its author suggested when none of the server's fitted
 * ([SubmitQuestionRequest.categorySuggestion]), as stored, or null for none. A question the author
 * filed under none is filed by the moderator when approving it.
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
    public val likeCount: Int = 0,
    public val dislikeCount: Int = 0,
    public val answerCount: Int = 0,
    public val votesA: Int = 0,
    public val votesB: Int = 0,
    public val authorId: String? = null,
    public val categorySuggestion: String? = null,
)

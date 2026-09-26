package io.ntole.wyr.core.question

import io.ntole.wyr.core.vote.VoteTallyDto
import kotlinx.serialization.Serializable

/**
 * A question as the moderator sees it in their list of every question
 * ([io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_QUESTIONS], CLAUDE.md §8d, *Moderation*): whatever its
 * status, a seed or a player's submission.
 *
 * [id], [optionA], [optionB] and [categories] are as in a [SubmissionDto], and [categories] is sent
 * and decoded as [QuestionDto.categories] is. [status] is where the question stands with the
 * moderator. [seed] is true for one of the server's starter questions, which nobody wrote and which is
 * approved from the start, and false for a player's submission.
 *
 * [authorId] names who wrote it, opaquely (CLAUDE.md §8d, *Moderation*): the author's player id, which
 * says nothing about them but which questions are theirs, and which
 * [io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_AUTHOR_BLOCKS] takes; never a username. Null for a seed.
 * Sent only to the moderator: no player's DTO carries it.
 *
 * [submittedAt] is when the server stored the question, submitted or seeded, and [reviewedAt] when a
 * moderator approved or rejected it, or null while none has, as for a seed. [retiredAt] is when a
 * moderator retired it, sent only for a [QuestionStatus.RETIRED] question: restoring one clears it.
 * All three are epoch milliseconds. [rejectionReason] is the moderator's short reason, sent only for
 * a [QuestionStatus.REJECTED] question and null for any other.
 *
 * [tally] is every player's latest answer to it, one vote per player, [likeCount] how many players
 * like it and [dislikeCount] how many dislike it. The server reads them in one statement, so they are
 * one moment's numbers. A retired question keeps them all, and no vote or reaction reaches it until it
 * is restored.
 *
 * [categories] must keep its default, and [status] its default, for a value added server-side to
 * decode on an older client — see [QuestionDto.categories] and [QuestionStatus].
 */
@Serializable
public data class AdminQuestionDto(
    public val id: String,
    public val optionA: String,
    public val optionB: String,
    public val categories: List<String> = emptyList(),
    public val status: QuestionStatus = QuestionStatus.UNKNOWN,
    public val seed: Boolean = false,
    public val submittedAt: Long,
    public val reviewedAt: Long? = null,
    public val retiredAt: Long? = null,
    public val rejectionReason: String? = null,
    public val tally: VoteTallyDto,
    public val likeCount: Int = 0,
    public val dislikeCount: Int = 0,
    public val authorId: String? = null,
)

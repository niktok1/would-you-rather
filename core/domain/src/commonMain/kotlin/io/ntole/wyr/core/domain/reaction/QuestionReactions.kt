package io.ntole.wyr.core.domain.reaction

/**
 * Where a question's reactions stand, as the server counted them (CLAUDE.md §8d, *Reactions*). The
 * client never works any of the numbers out itself.
 *
 * [likeCount] is how many players like the question [questionId] and [dislikeCount] how many dislike
 * it, this one among them as [myReaction] says. The server reads the three together, so they agree
 * with one another.
 */
public data class QuestionReactions(
    public val questionId: String,
    public val likeCount: Int,
    public val dislikeCount: Int,
    public val myReaction: Reaction,
)

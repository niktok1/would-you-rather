package io.ntole.wyr.core.reaction

import kotlinx.serialization.Serializable

/**
 * Where a question's reactions stand once a [ReactionRequest] has been applied.
 *
 * [likeCount] is how many players like the question and [dislikeCount] how many dislike it, the
 * requesting one among them as [myReaction] says. The server reads the three together, so they always
 * agree: a [myReaction] of [Reaction.LIKE] never comes with a [likeCount] of 0. [myReaction] is what
 * the server holds, which is what the request asked for unless another request of the same player's
 * landed meanwhile.
 *
 * Self-contained, as a [io.ntole.wyr.core.vote.VoteResultDto] is: [questionId] is echoed so a
 * response renders without its request.
 *
 * Carries no points. A like pays the question's author (CLAUDE.md §8c), no author travels on the
 * wire, and the author is usually somebody else. A player who likes their own question is paid for
 * it too, and sees it in their stats. A dislike pays and costs nobody anything.
 */
@Serializable
public data class ReactionResultDto(
    public val questionId: String,
    public val likeCount: Int,
    public val dislikeCount: Int,
    public val myReaction: Reaction,
)

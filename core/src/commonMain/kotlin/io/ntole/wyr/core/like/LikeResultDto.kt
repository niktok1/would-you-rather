package io.ntole.wyr.core.like

import kotlinx.serialization.Serializable

/**
 * Where a question's likes stand once a [LikeRequest] has been applied.
 *
 * [likeCount] is how many players like the question, the requesting one included when [likedByMe].
 * The server reads the two together, so they always agree: [likedByMe] never comes with a
 * [likeCount] of 0. [likedByMe] is what the server holds, which is what the request asked for
 * unless another request of the same player's landed meanwhile.
 *
 * Self-contained, as a [io.ntole.wyr.core.vote.VoteResultDto] is: [questionId] is echoed so a
 * response renders without its request.
 *
 * Carries no points. A like pays the question's author (CLAUDE.md §8d), no author travels on the
 * wire, and the author is usually somebody else. A player who likes their own question is paid for
 * it too, and sees it in their stats.
 */
@Serializable
public data class LikeResultDto(
    public val questionId: String,
    public val likeCount: Int,
    public val likedByMe: Boolean,
)

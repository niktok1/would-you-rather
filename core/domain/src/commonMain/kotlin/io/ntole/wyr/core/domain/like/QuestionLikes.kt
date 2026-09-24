package io.ntole.wyr.core.domain.like

/**
 * Where a question's likes stand, as the server counted them (CLAUDE.md §8d). The client never
 * works either number out itself.
 *
 * [likeCount] is how many players like the question [questionId], this one included when
 * [likedByMe]. The server reads the two together, so they agree with each other.
 */
public data class QuestionLikes(
    public val questionId: String,
    public val likeCount: Int,
    public val likedByMe: Boolean,
)

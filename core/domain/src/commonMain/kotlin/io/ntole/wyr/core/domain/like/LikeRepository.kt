package io.ntole.wyr.core.domain.like

/** Likes and unlikes questions for the session player. Implemented in `:core:data`. */
public interface LikeRepository {
    /**
     * Makes the session player like the question [questionId] when [liked], and not like it when
     * not, then reports where its likes stand (CLAUDE.md §8d). Any question the player is served may
     * be liked, their own included, answered or not.
     *
     * Sets the like rather than toggling it: asking for what already holds changes nothing and pays
     * nothing. So a call whose answer was lost can be made again exactly as it was, and leaves the
     * like as one call would.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, with
     *   [io.ntole.wyr.core.domain.error.DomainError.QUESTION_NOT_FOUND] for a question no player is
     *   served.
     */
    public suspend fun setLiked(
        questionId: String,
        liked: Boolean,
    ): QuestionLikes
}

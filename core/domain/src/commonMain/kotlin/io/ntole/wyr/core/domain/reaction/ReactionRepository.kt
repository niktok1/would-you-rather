package io.ntole.wyr.core.domain.reaction

/** Likes, dislikes and takes either back for the session player. Implemented in `:core:data`. */
public interface ReactionRepository {
    /**
     * Makes [reaction] what the session player thinks of the question [questionId], [Reaction.NONE]
     * for neither, then reports where its reactions stand (CLAUDE.md §8d, *Reactions*). Any question
     * the player is served may be reacted to, their own included, answered or not. A like replaces a
     * dislike, and a dislike a like.
     *
     * Sets the reaction rather than toggling it: asking for what already holds changes nothing and pays
     * nothing. So a call whose answer was lost can be made again exactly as it was, and leaves the
     * reaction as one call would.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, with
     *   [io.ntole.wyr.core.domain.error.DomainError.QUESTION_NOT_FOUND] for a question no player is
     *   served.
     */
    public suspend fun setReaction(
        questionId: String,
        reaction: Reaction,
    ): QuestionReactions
}

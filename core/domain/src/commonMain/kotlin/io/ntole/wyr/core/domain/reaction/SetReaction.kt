package io.ntole.wyr.core.domain.reaction

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Likes, dislikes or takes either back, guaranteeing a session exists first.
 *
 * A reaction is the session player's (CLAUDE.md §8d, *Reactions*), so it needs a session just as
 * voting does. Ensuring it here, as `CastVote` and `SkipQuestion` do, sends the first reaction with a
 * bearer instead of having it refused and retried.
 *
 * The reaction is what to hold from now on, not a toggle: see [ReactionRepository.setReaction].
 */
public class SetReaction(
    private val reactions: ReactionRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(
        questionId: String,
        reaction: Reaction,
    ): QuestionReactions {
        session.ensure()
        return reactions.setReaction(questionId, reaction)
    }
}

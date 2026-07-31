package io.ntole.wyr.core.domain.vote

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Casts a vote, guaranteeing a session exists first.
 *
 * This is the reason the use case earns its keep: voting requires an authenticated player, and
 * on a cold first launch there is no session yet. Ensuring it here means neither the UI nor the
 * vote repository has to know that auth is a precondition.
 */
public class CastVote(
    private val votes: VoteRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(
        questionId: String,
        side: Side,
    ): VoteOutcome {
        session.ensure()
        return votes.cast(questionId, side)
    }
}

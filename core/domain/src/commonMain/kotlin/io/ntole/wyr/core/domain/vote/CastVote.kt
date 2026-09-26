package io.ntole.wyr.core.domain.vote

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Casts a vote, guaranteeing a session exists first.
 *
 * This is the reason the use case earns its keep: voting requires an authenticated player, and
 * on a cold first launch there is no session yet. Ensuring it here means neither the UI nor the
 * vote repository has to know that auth is a precondition.
 *
 * [attempt] belongs to the caller: a new one per tap, and the same one again to retry that tap, with
 * the same [answerMillis], how long that tap took (CLAUDE.md §8b, *Personalization*).
 */
public class CastVote(
    private val votes: VoteRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(
        questionId: String,
        side: Side,
        attempt: AttemptId,
        answerMillis: Long? = null,
    ): VoteOutcome {
        session.ensure()
        return votes.cast(questionId, side, attempt, answerMillis)
    }
}

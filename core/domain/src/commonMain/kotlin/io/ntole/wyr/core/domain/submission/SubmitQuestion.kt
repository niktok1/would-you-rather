package io.ntole.wyr.core.domain.submission

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Submits a question of the player's own, guaranteeing a session exists first.
 *
 * The author is whoever the session names (CLAUDE.md §8d), so submitting needs a session just as
 * voting does, and on a cold first launch there is none yet. Ensuring it here, as `CastVote` does,
 * sends the first submission with a bearer instead of having it refused and retried.
 */
public class SubmitQuestion(
    private val submissions: SubmissionRepository,
    private val session: SessionRepository,
) {
    /**
     * @throws IllegalArgumentException when [categories], ids, is empty, before the session is
     *   ensured: a pick no question can be filed under sends nothing, not even the request that mints
     *   a guest.
     */
    public suspend operator fun invoke(
        optionA: String,
        optionB: String,
        categories: Set<String>,
    ): Submission {
        // The repository refuses it too, but only once the session is ensured, which on a cold start
        // has already minted a guest.
        require(categories.isNotEmpty()) { "a question is submitted under at least one category" }

        session.ensure()
        return submissions.submit(optionA, optionB, categories)
    }
}

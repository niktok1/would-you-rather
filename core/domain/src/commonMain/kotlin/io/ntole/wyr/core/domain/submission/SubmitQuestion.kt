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
     * [categories] are ids, none when nothing fits, and [categorySuggestion] the category the author
     * suggests then, or null ([SubmissionRepository.submit]).
     */
    public suspend operator fun invoke(
        optionA: String,
        optionB: String,
        categories: Set<String>,
        categorySuggestion: String? = null,
    ): Submission {
        session.ensure()
        return submissions.submit(optionA, optionB, categories, categorySuggestion)
    }
}

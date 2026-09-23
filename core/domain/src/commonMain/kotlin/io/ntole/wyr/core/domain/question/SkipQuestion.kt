package io.ntole.wyr.core.domain.question

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Skips a question, guaranteeing a session exists first.
 *
 * The server records a skip for the session player's current cycle (CLAUDE.md §8d), so skipping
 * needs a session just as voting does. Ensuring it here, as `CastVote` and `GetNextQuestion` do,
 * sends the first skip with a bearer instead of having it refused and retried.
 */
public class SkipQuestion(
    private val questions: QuestionRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(questionId: String) {
        session.ensure()
        questions.skip(questionId)
    }
}

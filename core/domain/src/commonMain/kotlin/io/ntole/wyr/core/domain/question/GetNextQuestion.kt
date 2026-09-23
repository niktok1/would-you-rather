package io.ntole.wyr.core.domain.question

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Hands out the next question to play, guaranteeing a session exists first.
 *
 * The feed is per player (CLAUDE.md §8d), so fetching needs a session just as voting does, and on
 * a cold first launch there is none yet. Ensuring it here, as `CastVote` does, sends the first
 * fetch with a bearer instead of having it refused and retried. The caller still never learns
 * whether the question came from the cache or the network.
 */
public class GetNextQuestion(
    private val questions: QuestionRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(): Question {
        session.ensure()
        return questions.next()
    }
}

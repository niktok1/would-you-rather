package io.ntole.wyr.core.domain.question

/**
 * Hands out the next question to play.
 *
 * Thin on purpose — the interesting part is that the caller never learns whether the question
 * came from the cache or the network.
 */
public class GetNextQuestion(
    private val questions: QuestionRepository,
) {
    public suspend operator fun invoke(): Question = questions.next()
}

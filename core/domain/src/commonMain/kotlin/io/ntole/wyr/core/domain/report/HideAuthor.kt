package io.ntole.wyr.core.domain.report

import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Hides every question by a question's author from the player for good, guaranteeing a session exists
 * first, as [ReportQuestion] does (see [ReportRepository.hideAuthor]); then drops the question queue,
 * which may hold other questions of theirs, fetched before the hide, so none of them is shown after it.
 * A refill in flight lands before the queue is dropped ([QuestionRepository.reset]). The client never
 * knows who wrote a question, so the whole queue goes.
 */
public class HideAuthor(
    private val reports: ReportRepository,
    private val questions: QuestionRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(questionId: String) {
        session.ensure()
        reports.hideAuthor(questionId)
        questions.reset()
    }
}

package io.ntole.wyr.core.domain.report

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Hides a question from the player for good, guaranteeing a session exists first, as [ReportQuestion]
 * does. See [ReportRepository.hideQuestion].
 */
public class HideQuestion(
    private val reports: ReportRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(questionId: String) {
        session.ensure()
        reports.hideQuestion(questionId)
    }
}

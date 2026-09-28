package io.ntole.wyr.core.domain.report

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Reports a question to the moderator, guaranteeing a session exists first: a report is the session
 * player's (CLAUDE.md §8d, *Reports*), as a vote is, and ensuring the session here sends it with a
 * bearer rather than having it refused and retried. See [ReportRepository.report].
 */
public class ReportQuestion(
    private val reports: ReportRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(
        questionId: String,
        reason: ReportReason,
    ) {
        session.ensure()
        reports.report(questionId, reason)
    }
}

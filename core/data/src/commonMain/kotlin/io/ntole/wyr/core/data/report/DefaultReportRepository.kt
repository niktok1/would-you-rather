package io.ntole.wyr.core.data.report

import io.ntole.wyr.core.data.mapper.toWire
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.withSessionRecovery
import io.ntole.wyr.core.domain.report.ReportReason
import io.ntole.wyr.core.domain.report.ReportRepository
import io.ntole.wyr.core.network.api.ReportApi
import io.ntole.wyr.core.report.HideAuthorRequest
import io.ntole.wyr.core.report.HideQuestionRequest
import io.ntole.wyr.core.report.ReportRequest

/**
 * Reports and hides through [withSessionRecovery], as reactions go: a dead session is replaced by one
 * fresh guest and the request sent again as it, unchanged. Any resend is safe (CLAUDE.md §8d,
 * *Reports*): a report sent again replaces its reason, and a hide sent again changes nothing. Nothing
 * here resends after any other failure.
 */
public class DefaultReportRepository(
    private val api: ReportApi,
    private val session: DefaultSessionRepository,
) : ReportRepository {
    override suspend fun report(
        questionId: String,
        reason: ReportReason,
    ) {
        val request = ReportRequest(questionId = questionId, reason = reason.toWire())
        session.withSessionRecovery { api.report(request) }
    }

    override suspend fun hideQuestion(questionId: String) {
        session.withSessionRecovery { api.hideQuestion(HideQuestionRequest(questionId)) }
    }

    override suspend fun hideAuthor(questionId: String) {
        session.withSessionRecovery { api.hideAuthor(HideAuthorRequest(questionId)) }
    }
}

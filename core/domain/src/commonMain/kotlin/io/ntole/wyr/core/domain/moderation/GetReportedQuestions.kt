package io.ntole.wyr.core.domain.moderation

/**
 * Lists the questions players reported, most reported first (CLAUDE.md §8d, *Moderation*, *Reports*).
 * Ensures no session, as [GetPendingSubmissions] explains.
 */
public class GetReportedQuestions(
    private val moderation: ModerationRepository,
) {
    public suspend operator fun invoke(token: AdminToken): List<ReportedQuestion> = moderation.reports(token)
}

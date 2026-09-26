package io.ntole.wyr.core.domain.moderation

import kotlin.time.Instant

/**
 * A question players reported, as the moderator's list of reported questions shows it (CLAUDE.md §8d,
 * *Moderation*, *Reports*): the [question] as the list of every question shows it, how many players
 * report it now, [reportCount], and how many of them give each reason, [reasons], most given first,
 * a reason nobody gave left out. The server reads them in one statement, so the counts add up to
 * [reportCount]. [lastReportedAt] is when a player last reported it. No reporter is known: the server
 * sends none.
 *
 * Deliberately not the same type as `AdminReportDto`: domain code must never see a DTO (CLAUDE.md §3).
 */
public data class ReportedQuestion(
    public val question: ModeratedQuestion,
    public val reportCount: Int,
    public val reasons: Map<ReportReason, Int>,
    public val lastReportedAt: Instant,
)

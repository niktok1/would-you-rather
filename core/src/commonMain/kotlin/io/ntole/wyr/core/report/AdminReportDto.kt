package io.ntole.wyr.core.report

import io.ntole.wyr.core.question.AdminQuestionDto
import kotlinx.serialization.Serializable

/**
 * A reported question as the moderator sees it ([io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_REPORTS],
 * CLAUDE.md §8d, *Reports*): the [question] as their list of every question shows it, how many players
 * report it now, [reportCount], and how many of those give each reason, [reasons], a reason nobody gave
 * left out, most given first. [lastReportedAt] is when a player last reported it, in epoch
 * milliseconds. The server reads the counts in one statement, so they add up to [reportCount]. No
 * reporter travels.
 *
 * [reasons] must keep its default, for a list to decode on an older client (CLAUDE.md §5).
 */
@Serializable
public data class AdminReportDto(
    public val question: AdminQuestionDto,
    public val reportCount: Int,
    public val reasons: List<ReportReasonCountDto> = emptyList(),
    public val lastReportedAt: Long,
)

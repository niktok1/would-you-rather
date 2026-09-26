package io.ntole.wyr.core.report

import kotlinx.serialization.Serializable

/**
 * How many of a question's reports give [reason], in the moderator's list of reported questions
 * ([AdminReportDto.reasons]).
 *
 * [reason] must keep its default, for a reason added server-side to decode on an older client (CLAUDE.md
 * §5): an element of a list is an object, so the client's `coerceInputValues` reaches its property.
 */
@Serializable
public data class ReportReasonCountDto(
    public val reason: ReportReason = ReportReason.UNKNOWN,
    public val count: Int = 0,
)

package io.ntole.wyr.core.report

import kotlinx.serialization.Serializable

/**
 * The reported questions, most reported first ([io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_REPORTS]).
 * [reports] must keep its default, for a list to decode on an older client (CLAUDE.md §5).
 */
@Serializable
public data class AdminReportListDto(
    public val reports: List<AdminReportDto> = emptyList(),
)

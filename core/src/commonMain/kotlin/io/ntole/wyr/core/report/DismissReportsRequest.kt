package io.ntole.wyr.core.report

import kotlinx.serialization.Serializable

/**
 * Clear every report of [questionId], the moderator having looked at it
 * ([io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_REPORT_DISMISSALS], CLAUDE.md §8d, *Reports*). The question
 * stays as it stands, and hidden from each player who reported it. [questionId] must not be blank or
 * hold a control character.
 */
@Serializable
public data class DismissReportsRequest(
    public val questionId: String,
)

package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * Every question the requesting player has submitted, whatever its status, newest first
 * (CLAUDE.md §8d).
 *
 * All of them in one answer, with no paging: only
 * [io.ntole.wyr.core.api.WyrApi.Limits.MAX_PENDING_SUBMISSIONS] can be pending at once, but nothing
 * bounds how many have been approved or rejected. A list inside an object rather than a bare
 * array, so a cursor can be added later without breaking a client that does not know it.
 */
@Serializable
public data class SubmissionListDto(
    public val submissions: List<SubmissionDto>,
)

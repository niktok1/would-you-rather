package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * Every question the requesting player has submitted, whatever its status, newest first
 * (CLAUDE.md §8d), or the moderator's queue, oldest first
 * ([io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_SUBMISSIONS]).
 *
 * The author's list comes whole, with no paging: only
 * [io.ntole.wyr.core.api.WyrApi.Limits.MAX_PENDING_SUBMISSIONS] can be pending at once, but nothing
 * bounds how many have been approved or rejected. The moderator's is bounded by its
 * [io.ntole.wyr.core.api.WyrApi.Query.LIMIT] instead, since it spans every player. A list inside an
 * object rather than a bare array, so a cursor can be added later without breaking a client that
 * does not know it.
 */
@Serializable
public data class SubmissionListDto(
    public val submissions: List<SubmissionDto>,
)

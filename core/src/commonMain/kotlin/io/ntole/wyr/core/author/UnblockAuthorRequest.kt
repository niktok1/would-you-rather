package io.ntole.wyr.core.author

import kotlinx.serialization.Serializable

/**
 * Let the author [authorId] names submit questions again ([io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_AUTHOR_UNBLOCKS],
 * CLAUDE.md §8d, *Moderation*). What the block rejected stays rejected.
 */
@Serializable
public data class UnblockAuthorRequest(
    public val authorId: String,
)

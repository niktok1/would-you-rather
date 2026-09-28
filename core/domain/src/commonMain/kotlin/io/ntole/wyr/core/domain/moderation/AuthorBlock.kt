package io.ntole.wyr.core.domain.moderation

/**
 * Where the author [authorId] stands once a moderator blocked or unblocked them (CLAUDE.md §8d,
 * *Moderation*, *Authors*), as the server answered: [isBlocked] from submitting or not, and how many
 * of their pending submissions the block rejected, [rejectedSubmissions], 0 for an unblock.
 *
 * The server says where an author stands only in answer to a block or an unblock: nothing it lists
 * carries it.
 */
public data class AuthorBlock(
    public val authorId: String,
    public val isBlocked: Boolean,
    public val rejectedSubmissions: Int,
)

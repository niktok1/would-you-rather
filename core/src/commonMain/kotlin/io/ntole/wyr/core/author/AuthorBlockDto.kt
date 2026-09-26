package io.ntole.wyr.core.author

import kotlinx.serialization.Serializable

/**
 * Where an author stands after a block or an unblock (CLAUDE.md §8d, *Moderation*): whether [blocked]
 * from submitting, and how many submissions of theirs this block rejected, [rejectedSubmissions], 0 for
 * an unblock and for a block of an author blocked already with nothing pending.
 */
@Serializable
public data class AuthorBlockDto(
    public val authorId: String,
    public val blocked: Boolean,
    public val rejectedSubmissions: Int = 0,
)

package io.ntole.wyr.core.domain.moderation

/**
 * Lets a blocked author submit again (CLAUDE.md §8d, *Moderation*, *Authors*); what the block rejected
 * stays rejected. Ensures no session, as [GetPendingSubmissions] explains.
 */
public class UnblockAuthor(
    private val moderation: ModerationRepository,
) {
    public suspend operator fun invoke(
        token: AdminToken,
        authorId: String,
    ): AuthorBlock = moderation.unblockAuthor(token, authorId)
}

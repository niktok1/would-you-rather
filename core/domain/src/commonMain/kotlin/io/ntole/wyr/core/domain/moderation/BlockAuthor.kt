package io.ntole.wyr.core.domain.moderation

/**
 * Blocks an author, named by the opaque id the admin lists give, from submitting, and rejects each of
 * their pending submissions with one reason, which they see beside each (CLAUDE.md §8d, *Moderation*,
 * *Authors*). A [RejectionReason] is one the server accepts, so none it would refuse can be sent.
 * Ensures no session, as [GetPendingSubmissions] explains.
 */
public class BlockAuthor(
    private val moderation: ModerationRepository,
) {
    public suspend operator fun invoke(
        token: AdminToken,
        authorId: String,
        reason: RejectionReason,
    ): AuthorBlock = moderation.blockAuthor(token, authorId, reason)
}

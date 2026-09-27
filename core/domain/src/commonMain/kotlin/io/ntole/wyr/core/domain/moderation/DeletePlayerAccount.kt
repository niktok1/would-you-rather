package io.ntole.wyr.core.domain.moderation

/**
 * Deletes the account [AccountRef] names on its player's request (CLAUDE.md §8a, *Deleting an account*,
 * *By a moderator*): everything of theirs goes, as it does when they delete it themselves, and it
 * cannot be undone. Ensures no session, as [GetPendingSubmissions] explains.
 */
public class DeletePlayerAccount(
    private val moderation: ModerationRepository,
) {
    public suspend operator fun invoke(
        token: AdminToken,
        account: AccountRef,
    ): Unit = moderation.deleteAccount(token, account)
}

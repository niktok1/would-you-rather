package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.submission.Submission

/**
 * Lists the submissions waiting for a moderator, oldest first (CLAUDE.md §8d, *Moderation*).
 *
 * Unlike the player's use cases, this ensures no session first, and nor do [ApproveSubmission] and
 * [RejectSubmission]: the moderator is whoever holds the [AdminToken], not a player, so a session
 * would only mint a guest for nothing on a device used to moderate.
 */
public class GetPendingSubmissions(
    private val moderation: ModerationRepository,
) {
    public suspend operator fun invoke(token: AdminToken): List<Submission> = moderation.pending(token)
}

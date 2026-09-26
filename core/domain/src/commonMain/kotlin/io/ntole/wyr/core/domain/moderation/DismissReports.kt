package io.ntole.wyr.core.domain.moderation

/**
 * Clears every report of a question the moderator looked at, which leaves the list of reported
 * questions until a player reports it again (CLAUDE.md §8d, *Moderation*, *Reports*). The question
 * stays as it stands. Ensures no session, as [GetPendingSubmissions] explains.
 */
public class DismissReports(
    private val moderation: ModerationRepository,
) {
    public suspend operator fun invoke(
        token: AdminToken,
        questionId: String,
    ): Unit = moderation.dismissReports(token, questionId)
}

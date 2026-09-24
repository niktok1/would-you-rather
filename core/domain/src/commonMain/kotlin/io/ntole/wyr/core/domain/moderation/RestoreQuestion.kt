package io.ntole.wyr.core.domain.moderation

/**
 * Restores a retired question, so it is served again, due for every player who has not done it in
 * their current cycle (CLAUDE.md §8d, *Moderation*). Ensures no session, as [GetPendingSubmissions]
 * explains.
 */
public class RestoreQuestion(
    private val moderation: ModerationRepository,
) {
    public suspend operator fun invoke(
        token: AdminToken,
        questionId: String,
    ): ModeratedQuestion = moderation.restore(token, questionId)
}

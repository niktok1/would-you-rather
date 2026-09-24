package io.ntole.wyr.core.domain.moderation

/**
 * Retires an approved question, a seed included, so it is served to nobody until [RestoreQuestion]
 * restores it; nothing it earned is taken back (CLAUDE.md §8d, *Moderation*). Ensures no session, as
 * [GetPendingSubmissions] explains.
 */
public class RetireQuestion(
    private val moderation: ModerationRepository,
) {
    public suspend operator fun invoke(
        token: AdminToken,
        questionId: String,
    ): ModeratedQuestion = moderation.retire(token, questionId)
}

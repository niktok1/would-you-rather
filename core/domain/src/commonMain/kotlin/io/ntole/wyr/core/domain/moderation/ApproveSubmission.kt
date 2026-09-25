package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.submission.Submission

/**
 * Approves a pending submission (CLAUDE.md §8d, *Moderation*), under the categories its author
 * picked, or under [categories], by id, in their place when there are any. Ensures no session, as
 * [GetPendingSubmissions] explains.
 */
public class ApproveSubmission(
    private val moderation: ModerationRepository,
) {
    public suspend operator fun invoke(
        token: AdminToken,
        questionId: String,
        categories: Set<String> = emptySet(),
    ): Submission = moderation.approve(token, questionId, categories)
}

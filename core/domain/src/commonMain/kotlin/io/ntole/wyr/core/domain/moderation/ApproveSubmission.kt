package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission

/**
 * Approves a pending submission (CLAUDE.md §8d, *Moderation*), under the categories its author
 * picked, or under [categories] in their place when there are any. Ensures no session, as
 * [GetPendingSubmissions] explains.
 */
public class ApproveSubmission(
    private val moderation: ModerationRepository,
) {
    /** @throws IllegalArgumentException as [ModerationRepository.approve] does, having sent nothing. */
    public suspend operator fun invoke(
        token: AdminToken,
        questionId: String,
        categories: Set<Category> = emptySet(),
    ): Submission = moderation.approve(token, questionId, categories)
}

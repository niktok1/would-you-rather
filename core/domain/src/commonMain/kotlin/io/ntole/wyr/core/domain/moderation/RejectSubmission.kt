package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.submission.Submission

/**
 * Rejects a pending submission with a reason its author sees (CLAUDE.md §8d, *Moderation*). A
 * [RejectionReason] is one the server accepts, so none it would refuse can be sent. Ensures no
 * session, as [GetPendingSubmissions] explains.
 */
public class RejectSubmission(
    private val moderation: ModerationRepository,
) {
    public suspend operator fun invoke(
        token: AdminToken,
        questionId: String,
        reason: RejectionReason,
    ): Submission = moderation.reject(token, questionId, reason)
}

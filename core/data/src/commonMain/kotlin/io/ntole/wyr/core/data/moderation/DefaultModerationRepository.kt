package io.ntole.wyr.core.data.moderation

import io.ntole.wyr.core.data.mapper.approveSubmissionRequest
import io.ntole.wyr.core.data.mapper.rejectSubmissionRequest
import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.network.api.ModerationApi

/**
 * Moderates through the admin routes, each call sending the token it is given (CLAUDE.md §8d,
 * *Moderation*).
 *
 * Through [runApi] alone, never `withSessionRecovery`, and with no session to hand: the admin routes
 * answer a wrong token 403, never 401, and no player session is the moderator's to recover. A 401
 * from one anyway, a proxy's say, reaches the caller as `UNAUTHORIZED`, instead of throwing the
 * player's session away for a new guest.
 */
public class DefaultModerationRepository(
    private val api: ModerationApi,
) : ModerationRepository {
    override suspend fun pending(token: AdminToken): List<Submission> =
        runApi { api.pending(token.value) }.submissions.map { it.toDomain() }

    override suspend fun approve(
        token: AdminToken,
        questionId: String,
        categories: Set<Category>,
    ): Submission {
        // Built before anything is sent, so a category no question can be filed under never leaves
        // the client.
        val request = approveSubmissionRequest(questionId, categories)

        return runApi { api.approve(token.value, request) }.toDomain()
    }

    override suspend fun reject(
        token: AdminToken,
        questionId: String,
        reason: RejectionReason,
    ): Submission = runApi { api.reject(token.value, rejectSubmissionRequest(questionId, reason)) }.toDomain()
}

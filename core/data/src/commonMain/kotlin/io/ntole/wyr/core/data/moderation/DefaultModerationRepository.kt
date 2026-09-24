package io.ntole.wyr.core.data.moderation

import io.ntole.wyr.core.data.mapper.approveSubmissionRequest
import io.ntole.wyr.core.data.mapper.rejectSubmissionRequest
import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.data.mapper.wireCategories
import io.ntole.wyr.core.data.mapper.wireStatuses
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.ModeratedQuestionPage
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.network.api.ModerationApi
import io.ntole.wyr.core.question.RestoreQuestionRequest
import io.ntole.wyr.core.question.RetireQuestionRequest

/**
 * Moderates through the admin routes, each call sending the token it is given (CLAUDE.md §8d,
 * *Moderation*).
 *
 * Through [runApi] alone, never `withSessionRecovery`, and with no session to hand: the admin routes
 * answer a wrong token 403, never 401, and no player session is the moderator's to recover. A 401
 * from one anyway still has the Auth plugin refresh the player's session, as every 401 does, but
 * nothing mints a guest in its place: it reaches the caller as `UNAUTHORIZED` when it carries the
 * server's `ErrorDto`, and as `UNKNOWN` without one, a proxy's say.
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

    override suspend fun questions(
        token: AdminToken,
        filter: QuestionFilter,
        after: QuestionCursor?,
    ): ModeratedQuestionPage {
        // Mapped before anything is sent, so a filter by what this build cannot name never leaves it.
        val statuses = filter.wireStatuses()
        val categories = filter.wireCategories()

        return runApi { api.questions(token.value, statuses, categories, cursor = after?.value) }.toDomain()
    }

    override suspend fun retire(
        token: AdminToken,
        questionId: String,
    ): ModeratedQuestion = runApi { api.retire(token.value, RetireQuestionRequest(questionId)) }.toDomain()

    override suspend fun restore(
        token: AdminToken,
        questionId: String,
    ): ModeratedQuestion = runApi { api.restore(token.value, RestoreQuestionRequest(questionId)) }.toDomain()
}

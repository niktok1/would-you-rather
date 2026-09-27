package io.ntole.wyr.core.data.moderation

import io.ntole.wyr.core.author.UnblockAuthorRequest
import io.ntole.wyr.core.category.CreateCategoryRequest
import io.ntole.wyr.core.category.RenameCategoryRequest
import io.ntole.wyr.core.data.mapper.approveSubmissionRequest
import io.ntole.wyr.core.data.mapper.blockAuthorRequest
import io.ntole.wyr.core.data.mapper.rejectSubmissionRequest
import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.data.mapper.toModeratorsSubmission
import io.ntole.wyr.core.data.mapper.wireCategories
import io.ntole.wyr.core.data.mapper.wireStatuses
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.moderation.AccountRef
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.AuthorBlock
import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.ModeratedQuestionPage
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.moderation.ReportedQuestion
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.network.api.ModerationApi
import io.ntole.wyr.core.player.DeleteAccountRequest
import io.ntole.wyr.core.question.RestoreQuestionRequest
import io.ntole.wyr.core.question.RetireQuestionRequest
import io.ntole.wyr.core.report.DismissReportsRequest

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
        runApi { api.pending(token.value, limit = ModerationRepository.PAGE_SIZE) }
            .submissions
            .map { it.toModeratorsSubmission() }

    override suspend fun approve(
        token: AdminToken,
        questionId: String,
        categories: Set<String>,
    ): Submission =
        runApi { api.approve(token.value, approveSubmissionRequest(questionId, categories)) }.toModeratorsSubmission()

    override suspend fun reject(
        token: AdminToken,
        questionId: String,
        reason: RejectionReason,
    ): Submission =
        runApi { api.reject(token.value, rejectSubmissionRequest(questionId, reason)) }.toModeratorsSubmission()

    override suspend fun questions(
        token: AdminToken,
        filter: QuestionFilter,
        after: QuestionCursor?,
    ): ModeratedQuestionPage {
        // Mapped before anything is sent, so a filter by a status this build cannot name never leaves it.
        val statuses = filter.wireStatuses()
        val categories = filter.wireCategories()
        val cursor = after?.value

        val page = runApi { api.questions(token.value, statuses, categories, cursor, ModerationRepository.PAGE_SIZE) }

        return page.toDomain()
    }

    override suspend fun retire(
        token: AdminToken,
        questionId: String,
    ): ModeratedQuestion = runApi { api.retire(token.value, RetireQuestionRequest(questionId)) }.toDomain()

    override suspend fun restore(
        token: AdminToken,
        questionId: String,
    ): ModeratedQuestion = runApi { api.restore(token.value, RestoreQuestionRequest(questionId)) }.toDomain()

    override suspend fun reports(token: AdminToken): List<ReportedQuestion> =
        runApi { api.reports(token.value, limit = ModerationRepository.PAGE_SIZE) }.reports.map { it.toDomain() }

    override suspend fun dismissReports(
        token: AdminToken,
        questionId: String,
    ): Unit = runApi { api.dismissReports(token.value, DismissReportsRequest(questionId)) }

    override suspend fun blockAuthor(
        token: AdminToken,
        authorId: String,
        reason: RejectionReason,
    ): AuthorBlock = runApi { api.blockAuthor(token.value, blockAuthorRequest(authorId, reason)) }.toDomain()

    override suspend fun unblockAuthor(
        token: AdminToken,
        authorId: String,
    ): AuthorBlock = runApi { api.unblockAuthor(token.value, UnblockAuthorRequest(authorId)) }.toDomain()

    override suspend fun deleteAccount(
        token: AdminToken,
        account: AccountRef,
    ): Unit = runApi { api.deleteAccount(token.value, account.toRequest()) }

    override suspend fun addCategory(
        token: AdminToken,
        id: String?,
        nameSr: String,
        nameEn: String,
    ): Category = runApi { api.addCategory(token.value, CreateCategoryRequest(id, nameSr, nameEn)) }.toDomain()

    override suspend fun renameCategory(
        token: AdminToken,
        id: String,
        nameSr: String,
        nameEn: String,
    ): Category = runApi { api.renameCategory(token.value, RenameCategoryRequest(id, nameSr, nameEn)) }.toDomain()
}

/** [this] as the server's request names it: by exactly one of the two. */
private fun AccountRef.toRequest(): DeleteAccountRequest =
    when (this) {
        is AccountRef.Username -> DeleteAccountRequest(username = username)
        is AccountRef.Id -> DeleteAccountRequest(accountId = accountId)
    }

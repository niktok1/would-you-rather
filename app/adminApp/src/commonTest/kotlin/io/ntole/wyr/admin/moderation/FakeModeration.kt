package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.ModeratedQuestionPage
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import kotlin.time.Instant

/**
 * The moderator's repository as the tests script it: each call is recorded in [calls], with the
 * token it carried in [tokens], and answered by the lambda of its name.
 */
class FakeModeration : ModerationRepository {
    val calls = mutableListOf<String>()
    val tokens = mutableListOf<AdminToken>()

    var pending: suspend () -> List<Submission> = { QUEUE }
    var approve: suspend (String, Set<Category>) -> Submission = { id, categories ->
        val submission = QUEUE.single { it.id == id }
        submission.copy(status = SubmissionStatus.APPROVED, categories = categories.ifEmpty { submission.categories })
    }
    var reject: suspend (String, RejectionReason) -> Submission = { id, reason ->
        QUEUE.single { it.id == id }.copy(status = SubmissionStatus.REJECTED, rejectionReason = reason.value)
    }

    override suspend fun pending(token: AdminToken): List<Submission> {
        tokens += token
        calls += "pending"
        return pending.invoke()
    }

    override suspend fun approve(
        token: AdminToken,
        questionId: String,
        categories: Set<Category>,
    ): Submission {
        tokens += token
        calls += "approve $questionId $categories"
        return approve.invoke(questionId, categories)
    }

    override suspend fun reject(
        token: AdminToken,
        questionId: String,
        reason: RejectionReason,
    ): Submission {
        tokens += token
        calls += "reject $questionId ${reason.value}"
        return reject.invoke(questionId, reason)
    }

    override suspend fun questions(
        token: AdminToken,
        filter: QuestionFilter,
        after: QuestionCursor?,
    ): ModeratedQuestionPage = error("not scripted: questions")

    override suspend fun retire(
        token: AdminToken,
        questionId: String,
    ): ModeratedQuestion = error("not scripted: retire")

    override suspend fun restore(
        token: AdminToken,
        questionId: String,
    ): ModeratedQuestion = error("not scripted: restore")

    companion object {
        const val TOKEN = "s3cret-admin-token"

        val FIRST =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.SUPERPOWERS),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
            )

        val SECOND =
            Submission(
                id = "q2",
                optionA = "Tea",
                optionB = "Coffee",
                categories = setOf(Category.FOOD, Category.OTHER),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_001L),
            )

        /** Oldest first, as the server lists the queue. */
        val QUEUE = listOf(FIRST, SECOND)
    }
}

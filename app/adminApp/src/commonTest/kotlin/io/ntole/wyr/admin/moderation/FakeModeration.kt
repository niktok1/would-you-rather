package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.AuthorBlock
import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.ModeratedQuestionPage
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.moderation.ReportReason
import io.ntole.wyr.core.domain.moderation.ReportedQuestion
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Tally
import kotlin.time.Instant

/**
 * The moderator's repository as the tests script it: each call is recorded in [calls], with the
 * token it carried in [tokens], and answered by the lambda of its name.
 */
class FakeModeration : ModerationRepository {
    val calls = mutableListOf<String>()
    val tokens = mutableListOf<AdminToken>()

    var pending: suspend () -> List<Submission> = { QUEUE }
    var approve: suspend (String, Set<String>) -> Submission = { id, categories ->
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
        categories: Set<String>,
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

    /** Every question, [LISTED], two a page, whatever the filter, unless a test scripts another. */
    var questions: suspend (
        QuestionFilter,
        QuestionCursor?,
    ) -> ModeratedQuestionPage = { _, after -> pageOf(LISTED, after) }
    var retire: suspend (String) -> ModeratedQuestion = { id ->
        LISTED.single { it.id == id }.copy(status = SubmissionStatus.RETIRED, retiredAt = RETIRED_AT)
    }
    var restore: suspend (String) -> ModeratedQuestion = { id ->
        LISTED.single { it.id == id }.copy(status = SubmissionStatus.APPROVED, retiredAt = null)
    }

    override suspend fun questions(
        token: AdminToken,
        filter: QuestionFilter,
        after: QuestionCursor?,
    ): ModeratedQuestionPage {
        tokens += token
        calls += "questions ${filter.statuses} ${filter.categories} after=${after?.value}"
        return questions.invoke(filter, after)
    }

    override suspend fun retire(
        token: AdminToken,
        questionId: String,
    ): ModeratedQuestion {
        tokens += token
        calls += "retire $questionId"
        return retire.invoke(questionId)
    }

    override suspend fun restore(
        token: AdminToken,
        questionId: String,
    ): ModeratedQuestion {
        tokens += token
        calls += "restore $questionId"
        return restore.invoke(questionId)
    }

    /** A category as the server stores it: its names trimmed, and FAST_FOOD for the id it makes. */
    var addCategory: suspend (String?, String, String) -> Category = { id, nameSr, nameEn ->
        Category(id ?: "FAST_FOOD", nameSr.trim(), nameEn.trim())
    }
    var renameCategory: suspend (String, String, String) -> Category = { id, nameSr, nameEn ->
        Category(id, nameSr.trim(), nameEn.trim())
    }

    override suspend fun addCategory(
        token: AdminToken,
        id: String?,
        nameSr: String,
        nameEn: String,
    ): Category {
        tokens += token
        calls += "addCategory $id|$nameSr|$nameEn"
        return addCategory.invoke(id, nameSr, nameEn)
    }

    override suspend fun renameCategory(
        token: AdminToken,
        id: String,
        nameSr: String,
        nameEn: String,
    ): Category {
        tokens += token
        calls += "renameCategory $id|$nameSr|$nameEn"
        return renameCategory.invoke(id, nameSr, nameEn)
    }

    /** [REPORTED], unless a test scripts another. */
    var reports: suspend () -> List<ReportedQuestion> = { REPORTED }
    var dismissReports: suspend (String) -> Unit = {}

    /** A block that rejected nothing, and an unblock, each as the server answers one. */
    var blockAuthor: suspend (String, RejectionReason) -> AuthorBlock = { id, _ -> AuthorBlock(id, true, 0) }
    var unblockAuthor: suspend (String) -> AuthorBlock = { id -> AuthorBlock(id, false, 0) }

    override suspend fun reports(token: AdminToken): List<ReportedQuestion> {
        tokens += token
        calls += "reports"
        return reports.invoke()
    }

    override suspend fun dismissReports(
        token: AdminToken,
        questionId: String,
    ) {
        tokens += token
        calls += "dismissReports $questionId"
        dismissReports.invoke(questionId)
    }

    override suspend fun blockAuthor(
        token: AdminToken,
        authorId: String,
        reason: RejectionReason,
    ): AuthorBlock {
        tokens += token
        calls += "blockAuthor $authorId ${reason.value}"
        return blockAuthor.invoke(authorId, reason)
    }

    override suspend fun unblockAuthor(
        token: AdminToken,
        authorId: String,
    ): AuthorBlock {
        tokens += token
        calls += "unblockAuthor $authorId"
        return unblockAuthor.invoke(authorId)
    }

    companion object {
        const val TOKEN = "s3cret-admin-token"

        val FIRST =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf("SUPERPOWERS"),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
            )

        val SECOND =
            Submission(
                id = "q2",
                optionA = "Tea",
                optionB = "Coffee",
                categories = setOf("FOOD", "FROM_THE_FUTURE"),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_001L),
            )

        /** Oldest first, as the server lists the queue. */
        val QUEUE = listOf(FIRST, SECOND)

        val RETIRED_AT: Instant = Instant.fromEpochMilliseconds(1_790_000_900_000L)

        /** A question as the list shows one, approved and a player's unless a test says otherwise. */
        fun listed(
            id: String,
            optionA: String = "A of $id",
            optionB: String = "B of $id",
            status: SubmissionStatus = SubmissionStatus.APPROVED,
            isSeed: Boolean = false,
        ): ModeratedQuestion =
            ModeratedQuestion(
                id = id,
                optionA = optionA,
                optionB = optionB,
                categories = setOf("ABSURD"),
                status = status,
                isSeed = isSeed,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_500_000L),
                reviewedAt = null,
                retiredAt = if (status == SubmissionStatus.RETIRED) RETIRED_AT else null,
                rejectionReason = if (status == SubmissionStatus.REJECTED) "a duplicate" else null,
                tally = Tally(votesA = 3, votesB = 1),
                likeCount = 2,
            )

        /** Newest first, as the server lists every question: one of each status, a seed among them. */
        val LISTED =
            listOf(
                listed("q1", "Fly", "Swim", status = SubmissionStatus.PENDING),
                listed("seed-1", "Cats", "Dogs", isSeed = true),
                listed("q3", "Sea", "Mountains", status = SubmissionStatus.RETIRED),
                listed("q4", "Tea", "Tea", status = SubmissionStatus.REJECTED),
                listed("q5", "Early", "Late"),
            )

        /** A reported question, [LISTED]'s [id], with its reports counted. */
        fun reported(
            id: String,
            reasons: Map<ReportReason, Int> = mapOf(ReportReason.OFFENSIVE to 2, ReportReason.SPAM to 1),
        ): ReportedQuestion =
            ReportedQuestion(
                question = LISTED.single { it.id == id },
                reportCount = reasons.values.sum(),
                reasons = reasons,
                lastReportedAt = Instant.fromEpochMilliseconds(1_790_000_800_000L),
            )

        /** Most reported first, as the server lists them: a player's question and a seed. */
        val REPORTED =
            listOf(
                reported("q5", mapOf(ReportReason.OFFENSIVE to 3, ReportReason.REAL_PERSON to 1)),
                reported("seed-1", mapOf(ReportReason.NOT_A_CHOICE to 1)),
            )

        /** The page of [questions] after [after], two a page, each cursor naming where it starts. */
        fun pageOf(
            questions: List<ModeratedQuestion>,
            after: QuestionCursor?,
        ): ModeratedQuestionPage {
            val start = after?.value?.toInt() ?: 0
            val end = minOf(start + PAGE_SIZE, questions.size)
            val next = if (end < questions.size) QuestionCursor("$end") else null
            return ModeratedQuestionPage(questions.subList(start, end), next)
        }

        const val PAGE_SIZE = 2
    }
}

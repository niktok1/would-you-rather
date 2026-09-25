package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Tally
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

/** Each use case sends what it is given, with the token, and nothing else: no session is involved. */
class ModerationUseCasesTest {
    private val moderation = RecordingModeration()
    private val token = assertNotNull(AdminToken.of("s3cret-token"))

    @Test
    fun `the pending submissions are listed with the token`() =
        runTest {
            assertEquals(listOf(PENDING), GetPendingSubmissions(moderation)(token))
            assertEquals(listOf("pending $token"), moderation.calls)
            assertEquals(listOf(token), moderation.tokens)
        }

    @Test
    fun `an approval sends the categories to file the submission under`() =
        runTest {
            val approved = ApproveSubmission(moderation)(token, "q1", setOf("FOOD", "ABSURD"))

            assertEquals(PENDING.copy(status = SubmissionStatus.APPROVED), approved)
            assertEquals(listOf("approve q1 [FOOD, ABSURD]"), moderation.calls)
            assertEquals(listOf(token), moderation.tokens)
        }

    @Test
    fun `an approval with no categories keeps the author's`() =
        runTest {
            ApproveSubmission(moderation)(token, "q1")

            assertEquals(listOf("approve q1 []"), moderation.calls)
        }

    @Test
    fun `a rejection sends its reason`() =
        runTest {
            val reason = assertNotNull(RejectionReason.of("a duplicate"))

            val rejected = RejectSubmission(moderation)(token, "q1", reason)

            assertEquals(PENDING.copy(status = SubmissionStatus.REJECTED, rejectionReason = "a duplicate"), rejected)
            assertEquals(listOf("reject q1 a duplicate"), moderation.calls)
            assertEquals(listOf(token), moderation.tokens)
        }

    @Test
    fun `every question is listed a page at a time with the token and the filter and the cursor`() =
        runTest {
            val filter = QuestionFilter(setOf(SubmissionStatus.RETIRED), setOf("FOOD"))

            val first = GetQuestions(moderation)(token)
            val next = GetQuestions(moderation)(token, filter, after = first.next)

            assertEquals(PAGE, first)
            assertEquals(PAGE, next)
            assertEquals(
                listOf(
                    "questions ${QuestionFilter()} null",
                    "questions $filter ${first.next}",
                ),
                moderation.calls,
            )
            assertEquals(listOf(token, token), moderation.tokens)
        }

    @Test
    fun `a retirement and a restoration send the question's id with the token`() =
        runTest {
            assertEquals(LISTED.copy(status = SubmissionStatus.RETIRED), RetireQuestion(moderation)(token, "q1"))
            assertEquals(LISTED, RestoreQuestion(moderation)(token, "q1"))

            assertEquals(listOf("retire q1", "restore q1"), moderation.calls)
            assertEquals(listOf(token, token), moderation.tokens)
        }

    private class RecordingModeration : ModerationRepository {
        val calls = mutableListOf<String>()
        val tokens = mutableListOf<AdminToken>()

        override suspend fun pending(token: AdminToken): List<Submission> {
            tokens += token
            calls += "pending $token"
            return listOf(PENDING)
        }

        override suspend fun approve(
            token: AdminToken,
            questionId: String,
            categories: Set<String>,
        ): Submission {
            tokens += token
            calls += "approve $questionId $categories"
            return PENDING.copy(status = SubmissionStatus.APPROVED)
        }

        override suspend fun reject(
            token: AdminToken,
            questionId: String,
            reason: RejectionReason,
        ): Submission {
            tokens += token
            calls += "reject $questionId ${reason.value}"
            return PENDING.copy(status = SubmissionStatus.REJECTED, rejectionReason = reason.value)
        }

        override suspend fun questions(
            token: AdminToken,
            filter: QuestionFilter,
            after: QuestionCursor?,
        ): ModeratedQuestionPage {
            tokens += token
            calls += "questions $filter $after"
            return PAGE
        }

        override suspend fun retire(
            token: AdminToken,
            questionId: String,
        ): ModeratedQuestion {
            tokens += token
            calls += "retire $questionId"
            return LISTED.copy(status = SubmissionStatus.RETIRED)
        }

        override suspend fun restore(
            token: AdminToken,
            questionId: String,
        ): ModeratedQuestion {
            tokens += token
            calls += "restore $questionId"
            return LISTED
        }
    }

    private companion object {
        val PENDING =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf("SUPERPOWERS"),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
            )

        val LISTED =
            ModeratedQuestion(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf("SUPERPOWERS"),
                status = SubmissionStatus.APPROVED,
                isSeed = false,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
                reviewedAt = Instant.fromEpochMilliseconds(1_790_000_001_000L),
                retiredAt = null,
                rejectionReason = null,
                tally = Tally(votesA = 2, votesB = 1),
                likeCount = 1,
            )

        val PAGE = ModeratedQuestionPage(listOf(LISTED), next = QuestionCursor("after-q1"))
    }
}

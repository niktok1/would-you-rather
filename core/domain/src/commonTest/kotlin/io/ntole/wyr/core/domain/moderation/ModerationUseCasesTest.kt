package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
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
            val approved = ApproveSubmission(moderation)(token, "q1", setOf(Category.FOOD, Category.RANDOM))

            assertEquals(PENDING.copy(status = SubmissionStatus.APPROVED), approved)
            assertEquals(listOf("approve q1 [FOOD, RANDOM]"), moderation.calls)
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
            categories: Set<Category>,
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
    }

    private companion object {
        val PENDING =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.SUPERPOWERS),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
            )
    }
}

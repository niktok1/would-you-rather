package io.ntole.wyr.dev.moderation

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.dev.LogEntry
import io.ntole.wyr.dev.LogResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ModerationConsoleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val moderation = FakeModeration()

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which has no implementation under test.
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is read when the section opens`() =
        runTest(dispatcher) {
            val viewModel = openSection()

            val state = viewModel.state.value
            // No token has been typed, and every admin route needs one.
            assertNull(state.pending)
            assertEquals(emptyList(), state.log)
            assertEquals(emptyList(), moderation.calls)
            assertFalse(state.isBusy)
        }

    @Test
    fun `nothing is sent until what is typed can be a token`() =
        runTest(dispatcher) {
            val viewModel = openSection()

            listOf("", "   ", "s3cret token", "s3crét").forEach { typed ->
                viewModel.setAdminToken(typed)
                assertNull(viewModel.state.value.token, "\"$typed\"")

                viewModel.loadPending()
                viewModel.approve("q1")
                viewModel.setReason("q1", "a duplicate")
                viewModel.reject("q1")
                testScheduler.advanceUntilIdle()
            }

            assertEquals(emptyList(), moderation.calls)
            assertEquals(emptyList(), viewModel.state.value.log)
        }

    @Test
    fun `Load pending lists the queue with the token as typed and trimmed`() =
        runTest(dispatcher) {
            val viewModel = openSection()

            viewModel.setAdminToken("  $TOKEN\n")
            viewModel.loadPending()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(QUEUE, state.pending)
            assertEquals(listOf(AdminToken.of(TOKEN)), moderation.tokens)
            assertEquals(listOf(LogEntry("loadPending", "", 0, LogResult.Ok("pending=2 next=q1"))), state.log)
        }

    @Test
    fun `Load pending is logged whether it works or not and a failure keeps the queue shown`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            moderation.pending = { throw WyrException(DomainError.FORBIDDEN, "wrong admin token") }

            viewModel.loadPending()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(
                    "loadPending" to LogResult.Err(DomainError.FORBIDDEN, "wrong admin token"),
                    "loadPending" to LogResult.Ok("pending=2 next=q1"),
                ),
                viewModel.state.value.log
                    .map { it.action to it.result },
            )
            assertEquals(QUEUE, viewModel.state.value.pending)
        }

    @Test
    fun `an approval with nothing picked keeps the author's categories and reads the queue again`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            moderation.pending = { listOf(SECOND) }

            viewModel.approve("q1")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("pending", "approve q1 []", "pending"), moderation.calls)
            assertEquals(
                LogEntry(
                    "approve",
                    "questionId=q1 categories=keep",
                    0,
                    LogResult.Ok("submission=q1 status=APPROVED categories=SUPERPOWERS"),
                ),
                viewModel.state.value.log
                    .first(),
            )
            assertEquals(listOf(SECOND), viewModel.state.value.pending)
        }

    @Test
    fun `the categories picked for a submission are the ones its approval files it under`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()

            viewModel.toggleCategory("q1", Category.RANDOM)
            viewModel.toggleCategory("q1", Category.ETHICS)
            viewModel.toggleCategory("q1", Category.FOOD)
            viewModel.toggleCategory("q1", Category.RANDOM)
            viewModel.toggleCategory("q2", Category.LIFESTYLE)
            viewModel.approve("q1")
            testScheduler.advanceUntilIdle()

            // In declaration order, and only q1's.
            assertEquals("approve q1 [FOOD, ETHICS]", moderation.calls[1])
            assertEquals(
                "questionId=q1 categories=FOOD,ETHICS",
                viewModel.state.value.log
                    .first()
                    .args,
            )
        }

    @Test
    fun `Reject sends nothing until the reason is one the server accepts`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            val tooLong = "x".repeat(RejectionReason.MAX_LENGTH + 1)
            val refused = listOf("", "  ", "a\nduplicate", "a\u2028duplicate", tooLong)

            refused.forEach { reason ->
                viewModel.setReason("q1", reason)
                assertNull(viewModel.state.value.rejectionOf("q1"), "\"$reason\"")

                viewModel.reject("q1")
                testScheduler.advanceUntilIdle()
            }

            assertEquals(listOf("pending"), moderation.calls)
        }

    @Test
    fun `a rejection sends the reason trimmed and logs it as typed`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()

            viewModel.setReason("q1", "  Too close to a seed ")
            viewModel.reject("q1")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("pending", "reject q1 Too close to a seed", "pending"), moderation.calls)
            assertEquals(
                LogEntry(
                    "reject",
                    "questionId=q1 reason=\"  Too close to a seed \"",
                    0,
                    LogResult.Ok("submission=q1 status=REJECTED categories=SUPERPOWERS reason=\"Too close to a seed\""),
                ),
                viewModel.state.value.log
                    .first(),
            )
        }

    @Test
    fun `every domain error a decision ends in is logged with its message and the queue is read again`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            viewModel.setReason("q1", "a duplicate")

            DomainError.entries.forEach { error ->
                moderation.approve = { _, _ -> throw WyrException(error, "the server's word on $error") }
                moderation.reject = { _, _ -> throw WyrException(error, "the server's word on $error") }
                moderation.calls.clear()

                viewModel.approve("q1")
                testScheduler.advanceUntilIdle()
                viewModel.reject("q1")
                testScheduler.advanceUntilIdle()

                val newest = viewModel.state.value.log
                assertEquals(
                    listOf(
                        "reject" to LogResult.Err(error, "the server's word on $error"),
                        "approve" to LogResult.Err(error, "the server's word on $error"),
                    ),
                    newest.take(2).map { it.action to it.result },
                )
                // Another moderator may have decided it, or the answer been lost after it was made.
                assertEquals(listOf("approve q1 []", "pending", "reject q1 a duplicate", "pending"), moderation.calls)
            }
        }

    @Test
    fun `a queue that cannot be read after a decision is logged on its own`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            moderation.pending = { throw WyrException(DomainError.NETWORK, "connect timed out") }

            viewModel.approve("q1")
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(
                    "refreshPending" to LogResult.Err(DomainError.NETWORK, "connect timed out"),
                    "approve" to LogResult.Ok("submission=q1 status=APPROVED categories=SUPERPOWERS"),
                ),
                viewModel.state.value.log
                    .take(2)
                    .map { it.action to it.result },
            )
            assertEquals(QUEUE, viewModel.state.value.pending)
        }

    @Test
    fun `what was picked for a submission no longer pending goes and the rest stays`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            viewModel.toggleCategory("q1", Category.FOOD)
            viewModel.toggleCategory("q2", Category.ETHICS)
            viewModel.setReason("q1", "a duplicate")
            viewModel.setReason("q2", "half typed")
            moderation.pending = { listOf(SECOND) }

            viewModel.approve("q1")
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(mapOf("q2" to setOf(Category.ETHICS)), state.newCategories)
            assertEquals(mapOf("q2" to "half typed"), state.reasons)
        }

    @Test
    fun `the token shows nowhere in the state or its log`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            viewModel.setReason("q1", "a duplicate")

            viewModel.approve("q1")
            testScheduler.advanceUntilIdle()
            viewModel.reject("q1")
            testScheduler.advanceUntilIdle()

            // The state's text is what a failed assertion or a stray println would show.
            val shown = viewModel.state.value.toString()
            assertFalse(TOKEN in shown, "the token shows in $shown")
            assertEquals(TOKEN, viewModel.state.value.adminToken.text)
        }

    @Test
    fun `nothing is decided while the queue is being read`() =
        runTest(dispatcher) {
            val queue = CompletableDeferred<List<Submission>>()
            moderation.pending = { queue.await() }
            val viewModel = openSection()
            viewModel.setAdminToken(TOKEN)

            viewModel.loadPending()
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value.isBusy)
            viewModel.approve("q1")
            queue.complete(QUEUE)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("pending"), moderation.calls)
            assertFalse(viewModel.state.value.isBusy)
        }

    @Test
    fun `anything else thrown is logged as Crash`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            moderation.approve = { _, _ -> error("boom") }

            viewModel.approve("q1")
            testScheduler.advanceUntilIdle()

            assertEquals(
                LogResult.Crash("IllegalStateException", "boom"),
                viewModel.state.value.log
                    .first { it.action == "approve" }
                    .result,
            )
        }

    @Test
    fun `cancellation is not logged as a failure`() =
        runTest(dispatcher) {
            val viewModel = openSection()
            viewModel.setAdminToken(TOKEN)
            moderation.pending = { throw CancellationException("caller went away") }

            viewModel.loadPending()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), viewModel.state.value.log)
            assertFalse(viewModel.state.value.isBusy)
        }

    private fun TestScope.openSection(): ModerationConsoleViewModel =
        ModerationConsoleViewModel(
            getPendingSubmissions = GetPendingSubmissions(moderation),
            approveSubmission = ApproveSubmission(moderation),
            rejectSubmission = RejectSubmission(moderation),
            // Virtual time, so an elapsed time is exactly what the fakes delayed.
            timeSource = dispatcher.scheduler.timeSource,
        ).also { testScheduler.advanceUntilIdle() }

    /** A section with the token typed and [QUEUE] loaded, its read the one call so far. */
    private fun TestScope.openWithQueue(): ModerationConsoleViewModel =
        openSection().also { viewModel ->
            viewModel.setAdminToken(TOKEN)
            viewModel.loadPending()
            testScheduler.advanceUntilIdle()
        }

    private class FakeModeration : ModerationRepository {
        val calls = mutableListOf<String>()
        val tokens = mutableListOf<AdminToken>()

        var pending: suspend () -> List<Submission> = { QUEUE }
        var approve: suspend (String, Set<Category>) -> Submission = { id, categories ->
            val submission = QUEUE.single { it.id == id }
            val filedUnder = categories.ifEmpty { submission.categories }
            submission.copy(status = SubmissionStatus.APPROVED, categories = filedUnder)
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
    }

    private companion object {
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

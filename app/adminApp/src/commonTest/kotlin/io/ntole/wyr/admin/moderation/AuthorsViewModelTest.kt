package io.ntole.wyr.admin.moderation

import io.ntole.wyr.admin.moderation.FakeModeration.Companion.TOKEN
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.moderation.AuthorBlock
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
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** Blocking and unblocking a question's author, from any tab that shows one. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthorsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val moderation = FakeModeration()
    private val categories = FakeCategories()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Block asks first and sends nothing until a reason the server takes is confirmed`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()

            viewModel.askToBlock("author-1", "q1", Screen.PENDING)
            assertEquals(BlockDraft("author-1", "q1", Screen.PENDING), viewModel.state.value.blocking)
            viewModel.confirmBlock()
            viewModel.setBlockReason("not\none line")
            viewModel.confirmBlock()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("pending"), moderation.calls, "no reason, then one the server refuses")
            viewModel.cancelBlock()
            assertNull(viewModel.state.value.blocking)
        }

    @Test
    fun `nothing asks to block without a token`() =
        runTest(dispatcher) {
            val viewModel = open()

            viewModel.askToBlock("author-1", "q1", Screen.PENDING)

            assertNull(viewModel.state.value.blocking)
        }

    @Test
    fun `a block sends the author and the reason trimmed and keeps where they stand`() =
        runTest(dispatcher) {
            moderation.blockAuthor = { id, _ -> AuthorBlock(id, isBlocked = true, rejectedSubmissions = 2) }
            val viewModel = openWithQueue()

            viewModel.askToBlock("author-1", "q1", Screen.PENDING)
            viewModel.setBlockReason("  ${READY_REASONS[1]} ")
            viewModel.confirmBlock()
            testScheduler.advanceUntilIdle()

            assertEquals("blockAuthor author-1 ${READY_REASONS[1]}", moderation.calls[1])
            val state = viewModel.state.value
            assertNull(state.blocking)
            assertEquals(mapOf("author-1" to true), state.authors)
            assertEquals(
                "Blocked author author-1: they can submit nothing until unblocked; 2 pending questions of theirs rejected.",
                state.pending.outcomes.notice,
            )
        }

    @Test
    fun `a block reads again what was read and only that`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            viewModel.loadReports()
            testScheduler.advanceUntilIdle()
            moderation.calls.clear()

            viewModel.askToBlock("author-of-q5", "q5", Screen.REPORTS)
            viewModel.setBlockReason(READY_REASONS[0])
            viewModel.confirmBlock()
            testScheduler.advanceUntilIdle()

            // Their pending questions are rejected: the queue shows it, and so would the list, had it been read.
            assertEquals(listOf("blockAuthor author-of-q5 ${READY_REASONS[0]}", "pending", "reports"), moderation.calls)
            assertEquals(null, viewModel.state.value.questions.questions)
        }

    @Test
    fun `a block reads the list again as deep as it was shown`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.setAdminToken(TOKEN)
            viewModel.loadQuestions()
            testScheduler.advanceUntilIdle()
            viewModel.loadMore()
            testScheduler.advanceUntilIdle()
            moderation.calls.clear()

            viewModel.askToBlock("author-of-q1", "q1", Screen.QUESTIONS)
            viewModel.setBlockReason(READY_REASONS[3])
            viewModel.confirmBlock()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(
                    "blockAuthor author-of-q1 ${READY_REASONS[3]}",
                    "questions [] [] after=null",
                    "questions [] [] after=2",
                ),
                moderation.calls,
            )
        }

    @Test
    fun `a block that failed shows under its question and what was read is read again`() =
        runTest(dispatcher) {
            moderation.blockAuthor = { _, _ -> throw WyrException(DomainError.NETWORK, "lost") }
            val viewModel = openWithQueue()

            viewModel.askToBlock("author-1", "q1", Screen.PENDING)
            viewModel.setBlockReason(READY_REASONS[0])
            viewModel.confirmBlock()
            testScheduler.advanceUntilIdle()

            // Its answer lost, it may have rejected what they had pending: only a read says.
            assertEquals(listOf("pending", "blockAuthor author-1 ${READY_REASONS[0]}", "pending"), moderation.calls)
            val state = viewModel.state.value
            assertEquals(
                ItemFailure("\"Fly\" or \"Swim\"", Failure.Refused(DomainError.NETWORK, detail = "lost")),
                state.pending.outcomes.failures["q1"],
            )
            assertEquals(emptyMap(), state.authors, "the server said nothing of where they stand")
        }

    @Test
    fun `nothing is read again after a block refused as a wrong token or by the rate limit`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()

            listOf(
                WyrException(DomainError.FORBIDDEN, "wrong admin token"),
                WyrException(DomainError.RATE_LIMITED, "slow down", retryAfter = 30.seconds),
            ).forEach { refusal ->
                moderation.blockAuthor = { _, _ -> throw refusal }
                moderation.calls.clear()

                viewModel.askToBlock("author-1", "q1", Screen.PENDING)
                viewModel.setBlockReason(READY_REASONS[0])
                viewModel.confirmBlock()
                testScheduler.advanceUntilIdle()

                assertEquals(listOf("blockAuthor author-1 ${READY_REASONS[0]}"), moderation.calls, refusal.error.name)
            }
        }

    @Test
    fun `an author no player is says so under the question`() =
        runTest(dispatcher) {
            moderation.unblockAuthor = { throw WyrException(DomainError.AUTHOR_NOT_FOUND, "no author-1") }
            val viewModel = openWithQueue()

            viewModel.unblock("author-1", "q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()

            assertEquals(
                Failure.Refused(DomainError.AUTHOR_NOT_FOUND, detail = "no author-1"),
                viewModel.state.value.pending.outcomes.failures["q1"]
                    ?.failure,
            )
        }

    @Test
    fun `an unblock sends at once and keeps where they stand and reads nothing again`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            viewModel.askToBlock("author-1", "q1", Screen.PENDING)
            viewModel.setBlockReason(READY_REASONS[0])
            viewModel.confirmBlock()
            testScheduler.advanceUntilIdle()
            moderation.calls.clear()

            viewModel.unblock("author-1", "q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()

            // Nothing the server lists shows where an author stands.
            assertEquals(listOf("unblockAuthor author-1"), moderation.calls)
            val state = viewModel.state.value
            assertEquals(mapOf("author-1" to false), state.authors)
            assertEquals("Unblocked author author-1: they may submit again.", state.pending.outcomes.notice)
        }

    @Test
    fun `Lock forgets where each author stands and the block asked for`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            viewModel.unblock("author-2", "q2", Screen.PENDING)
            testScheduler.advanceUntilIdle()
            viewModel.askToBlock("author-1", "q1", Screen.PENDING)

            viewModel.lock()

            assertEquals(emptyMap(), viewModel.state.value.authors)
            assertNull(viewModel.state.value.blocking)
        }

    private fun TestScope.open(): ModerationViewModel =
        moderationViewModelOver(moderation, categories).also { testScheduler.advanceUntilIdle() }

    /** The app with the token typed and the queue read, the one call so far. */
    private fun TestScope.openWithQueue(): ModerationViewModel =
        open().also { viewModel ->
            viewModel.setAdminToken(TOKEN)
            viewModel.loadPending()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("pending"), moderation.calls)
        }
}

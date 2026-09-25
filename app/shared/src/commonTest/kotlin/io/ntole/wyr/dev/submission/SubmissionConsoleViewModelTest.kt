package io.ntole.wyr.dev.submission

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.submission.SubmitQuestion
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
class SubmissionConsoleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val calls = mutableListOf<String>()
    private val sessions = FakeSessions(calls)
    private val submissions = FakeSubmissions(calls)

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
    fun `opening the section lists the author's submissions as the player it read them as`() =
        runTest(dispatcher) {
            val viewModel = openSection()

            val state = viewModel.state.value
            assertEquals(MINE, state.submissions)
            assertEquals("p1", state.listedFor)
            // The session first, as every use case makes sure of it.
            assertEquals(listOf("ensure", "mine"), calls)
            // A read that works is not an action of its own, so it is not logged.
            assertEquals(emptyList(), state.log)
            assertFalse(state.isBusy)
        }

    @Test
    fun `a list that cannot be read when the section opens is logged on its own`() =
        runTest(dispatcher) {
            submissions.mine = { throw WyrException(DomainError.NETWORK, "connect timed out") }

            val viewModel = openSection()

            val state = viewModel.state.value
            assertNull(state.submissions)
            assertEquals(
                listOf(LogEntry("refreshSubmissions", "", 0, LogResult.Err(DomainError.NETWORK, "connect timed out"))),
                state.log,
            )
        }

    @Test
    fun `a stored submission is logged as the server stored it and the list is read again`() =
        runTest(dispatcher) {
            val viewModel = openSection()
            calls.clear()
            submissions.mine = { listOf(STORED) + MINE }

            viewModel.write(" Fly ", "Swim", Category.SUPERPOWERS, Category.FOOD)
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(
                    "ensure",
                    """submit " Fly "|"Swim"|[FOOD, SUPERPOWERS]""",
                    "ensure",
                    "mine",
                ),
                calls,
            )
            assertEquals(
                LogEntry(
                    "submit",
                    """optionA=" Fly " optionB="Swim" categories=FOOD,SUPERPOWERS""",
                    0,
                    LogResult.Ok("""submission=q3 status=PENDING categories=FOOD,SUPERPOWERS A="Fly" B="Swim""""),
                ),
                viewModel.state.value.log
                    .single(),
            )
            assertEquals(listOf(STORED) + MINE, viewModel.state.value.submissions)
        }

    @Test
    fun `a stored submission clears the options and keeps the categories`() =
        runTest(dispatcher) {
            val viewModel = openSection()

            viewModel.write("Fly", "Swim", Category.FOOD)
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("", state.optionA)
            assertEquals("", state.optionB)
            assertEquals(setOf(Category.FOOD), state.categories)
            // So the same question is not stored twice by a second tap.
            assertFalse(state.canSubmit)
        }

    @Test
    fun `options typed while a submission is in flight are kept once it is stored`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<Submission>()
            submissions.submit = { _, _, _ -> answer.await() }
            val viewModel = openSection()
            viewModel.write("Fly", "Swim", Category.FOOD)
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            viewModel.setOptionA("Run")
            answer.complete(STORED)
            testScheduler.advanceUntilIdle()

            // The next question's first option, not the one just stored.
            assertEquals("Run", viewModel.state.value.optionA)
            assertEquals("", viewModel.state.value.optionB)
        }

    @Test
    fun `every domain error a submission ends in is logged with its message and keeps the question`() =
        runTest(dispatcher) {
            val viewModel = openSection()
            viewModel.write("Fly", "Swim", Category.FOOD)

            DomainError.entries.forEach { error ->
                submissions.submit = { _, _, _ -> throw WyrException(error, "the server's word on $error") }

                viewModel.submit()
                testScheduler.advanceUntilIdle()

                assertEquals(
                    LogResult.Err(error, "the server's word on $error"),
                    viewModel.state.value.log
                        .first()
                        .result,
                )
            }

            // Kept, so a question the server's rules refused can be put right and sent again.
            val state = viewModel.state.value
            assertEquals("Fly", state.optionA)
            assertEquals("Swim", state.optionB)
            assertEquals(DomainError.entries.size, state.log.size)
        }

    @Test
    fun `a refused submission still reads the list again`() =
        runTest(dispatcher) {
            val viewModel = openSection()
            calls.clear()
            // One whose answer was lost may have been stored, and the list is where it shows.
            submissions.submit = { _, _, _ -> throw WyrException(DomainError.NETWORK, "read timed out") }

            viewModel.write("Fly", "Swim", Category.FOOD)
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            assertEquals("mine", calls.last())
        }

    @Test
    fun `anything else thrown is logged as Crash`() =
        runTest(dispatcher) {
            val viewModel = openSection()
            submissions.submit = { _, _, _ -> error("boom") }

            viewModel.write("Fly", "Swim", Category.FOOD)
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            assertEquals(
                LogResult.Crash("IllegalStateException", "boom"),
                viewModel.state.value.log
                    .single()
                    .result,
            )
        }

    @Test
    fun `cancellation is not logged as a failure`() =
        runTest(dispatcher) {
            val viewModel = openSection()
            submissions.submit = { _, _, _ -> throw CancellationException("caller went away") }

            viewModel.write("Fly", "Swim", Category.FOOD)
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), viewModel.state.value.log)
            assertFalse(viewModel.state.value.isBusy)
        }

    @Test
    fun `Submit sends nothing until both options are typed and a category is picked`() =
        runTest(dispatcher) {
            val viewModel = openSection()
            calls.clear()
            val incomplete =
                listOf(
                    Triple("", "Swim", setOf(Category.FOOD)),
                    Triple("Fly", "", setOf(Category.FOOD)),
                    // Blank is not typed: the server would refuse it anyway.
                    Triple("  ", "Swim", setOf(Category.FOOD)),
                    Triple("Fly", " \t", setOf(Category.FOOD)),
                    // The server refuses none as malformed, so the picker must have one first.
                    Triple("Fly", "Swim", emptySet()),
                )

            incomplete.forEach { (optionA, optionB, categories) ->
                viewModel.write(optionA, optionB, *categories.toTypedArray())
                assertFalse(viewModel.state.value.canSubmit, "$optionA|$optionB|$categories")

                viewModel.submit()
                testScheduler.advanceUntilIdle()
            }

            assertEquals(emptyList(), calls)
            assertEquals(emptyList(), viewModel.state.value.log)

            viewModel.write("Fly", "Swim", Category.FOOD)
            assertTrue(viewModel.state.value.canSubmit)
        }

    @Test
    fun `nothing is submitted while the list is still being read`() =
        runTest(dispatcher) {
            val list = CompletableDeferred<List<Submission>>()
            submissions.mine = { list.await() }
            val viewModel = openSection()
            assertTrue(viewModel.state.value.isBusy)

            viewModel.write("Fly", "Swim", Category.FOOD)
            viewModel.submit()
            list.complete(MINE)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("ensure", "mine"), calls)
            assertEquals(emptyList(), viewModel.state.value.log)
        }

    @Test
    fun `a category toggles in and out and the picks read in declaration order`() =
        runTest(dispatcher) {
            val viewModel = openSection()

            viewModel.toggleCategory(Category.RANDOM)
            viewModel.toggleCategory(Category.FOOD)
            viewModel.toggleCategory(Category.ETHICS)
            viewModel.toggleCategory(Category.RANDOM)

            assertEquals(
                listOf(Category.FOOD, Category.ETHICS),
                viewModel.state.value.categories
                    .toList(),
            )
        }

    @Test
    fun `Refresh is logged whether it works or not`() =
        runTest(dispatcher) {
            val viewModel = openSection()

            viewModel.listSubmissions()
            testScheduler.advanceUntilIdle()
            submissions.mine = { throw WyrException(DomainError.SERVER, "HTTP 503") }
            viewModel.listSubmissions()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(
                    "listSubmissions" to LogResult.Err(DomainError.SERVER, "HTTP 503"),
                    "listSubmissions" to LogResult.Ok("submissions=2 pending=1"),
                ),
                viewModel.state.value.log
                    .map { it.action to it.result },
            )
            // A read that fails keeps what was shown.
            assertEquals(MINE, viewModel.state.value.submissions)
        }

    @Test
    fun `a list read as a fresh guest names that guest`() =
        runTest(dispatcher) {
            val viewModel = openSection()
            // What session recovery leaves behind a read refused as a dead session.
            submissions.mine = {
                sessions.stored = "p2"
                emptyList()
            }

            viewModel.listSubmissions()
            testScheduler.advanceUntilIdle()

            assertEquals("p2", viewModel.state.value.listedFor)
            assertEquals(emptyList(), viewModel.state.value.submissions)
        }

    /** A section just opened, its first read of the list done. */
    private fun TestScope.openSection(): SubmissionConsoleViewModel =
        SubmissionConsoleViewModel(
            submitQuestion = SubmitQuestion(submissions, sessions),
            getMySubmissions = GetMySubmissions(submissions, sessions),
            sessions = sessions,
            // Virtual time, so an elapsed time is exactly what the fakes delayed.
            timeSource = dispatcher.scheduler.timeSource,
        ).also { testScheduler.advanceUntilIdle() }

    /** Types both options and picks exactly [categories]. */
    private fun SubmissionConsoleViewModel.write(
        optionA: String,
        optionB: String,
        vararg categories: Category,
    ) {
        setOptionA(optionA)
        setOptionB(optionB)
        state.value.categories.forEach(this::toggleCategory)
        categories.forEach(this::toggleCategory)
    }

    private companion object {
        val STORED =
            Submission(
                id = "q3",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.FOOD, Category.SUPERPOWERS),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_002L),
            )

        val MINE =
            listOf(
                Submission(
                    id = "q2",
                    optionA = "Tea",
                    optionB = "Coffee",
                    categories = setOf(Category.FOOD),
                    status = SubmissionStatus.REJECTED,
                    rejectionReason = "a duplicate",
                    submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_001L),
                ),
                Submission(
                    id = "q1",
                    optionA = "Lie",
                    optionB = "Steal",
                    categories = setOf(Category.ETHICS),
                    status = SubmissionStatus.PENDING,
                    rejectionReason = null,
                    submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
                ),
            )
    }

    /** Records what the use cases called, in order, into the list shared with [FakeSubmissions]. */
    private class FakeSessions(
        private val calls: MutableList<String>,
    ) : SessionRepository {
        /** What [currentPlayerId] reports: the player the last [ensure] made sure of, until changed. */
        var stored: String? = null

        override suspend fun ensure(): String {
            calls += "ensure"
            return stored ?: "p1".also { stored = it }
        }

        override suspend fun currentPlayerId(): String? = stored

        override suspend fun clear() {
            stored = null
        }
    }

    private class FakeSubmissions(
        private val calls: MutableList<String>,
    ) : SubmissionRepository {
        var submit: suspend (String, String, Set<Category>) -> Submission = { _, _, _ -> STORED }
        var mine: suspend () -> List<Submission> = { MINE }

        override suspend fun submit(
            optionA: String,
            optionB: String,
            categories: Set<Category>,
        ): Submission {
            calls += "submit \"$optionA\"|\"$optionB\"|$categories"
            return submit.invoke(optionA, optionB, categories)
        }

        override suspend fun mine(): List<Submission> {
            calls += "mine"
            return mine.invoke()
        }
    }
}

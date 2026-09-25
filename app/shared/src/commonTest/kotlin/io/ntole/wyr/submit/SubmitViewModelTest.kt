package io.ntole.wyr.submit

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import io.ntole.wyr.core.domain.submission.OptionProblem
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.domain.submission.SubmissionRules
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.submission.SubmitQuestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SubmitViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val server = FakeServer()
    private val categories = FakeCategoryRepository()

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
    fun `nothing is read until the screen asks`() =
        runTest(dispatcher) {
            viewModel()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), server.calls)
            assertEquals(0, categories.reads)
        }

    @Test
    fun `showing the screen reads the categories to pick from in the server's order`() =
        runTest(dispatcher) {
            val viewModel = open()

            assertEquals(1, categories.reads)
            assertEquals(FakeCategoryRepository.LISTED, viewModel.state.value.categoryOptions)
            assertNull(viewModel.state.value.categoriesFailure)
            // A moderator adds one, and the next time the screen is shown lists it.
            val animals = Category(id = "ANIMALS", nameSr = "Животиње", nameEn = "Animals")
            categories.read = { FakeCategoryRepository.LISTED + animals }
            viewModel.refresh()
            testScheduler.advanceUntilIdle()
            assertEquals(FakeCategoryRepository.LISTED + animals, viewModel.state.value.categoryOptions)
        }

    @Test
    fun `the categories another screen read are there to pick from before this one reads them`() =
        runTest(dispatcher) {
            categories.refresh()

            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()

            assertEquals(FakeCategoryRepository.LISTED, viewModel.state.value.categoryOptions)
        }

    @Test
    fun `categories that cannot be read say so and keep those read before and the list is read all the same`() =
        runTest(dispatcher) {
            val viewModel = open()
            server.calls.clear()
            categories.read = { throw WyrException(DomainError.NETWORK) }

            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(SubmitFailure(DomainError.NETWORK), state.categoriesFailure)
            assertEquals(FakeCategoryRepository.LISTED, state.categoryOptions)
            assertEquals(listOf("ensure", "mine"), server.calls)
            // Try again reads them again.
            categories.read = { FakeCategoryRepository.LISTED }
            viewModel.refresh()
            testScheduler.advanceUntilIdle()
            assertNull(viewModel.state.value.categoriesFailure)
        }

    @Test
    fun `showing the screen lists the player's submissions newest first`() =
        runTest(dispatcher) {
            val state = open().state.value

            assertEquals(MINE, state.submissions)
            // The session first, as every use case makes sure of it.
            assertEquals(listOf("ensure", "mine"), server.calls)
            assertNull(state.submitFailure)
            assertNull(state.listFailure)
            assertFalse(state.isBusy)
        }

    @Test
    fun `an empty form names no problem and cannot be sent`() =
        runTest(dispatcher) {
            val state = open().state.value

            assertNull(state.optionAProblem)
            assertNull(state.optionBProblem)
            assertFalse(state.sameOptions)
            assertFalse(state.canSubmit)
        }

    @Test
    fun `each option says what the rules refuse as it is typed and nothing is sent`() =
        runTest(dispatcher) {
            val viewModel = open()
            server.calls.clear()
            viewModel.toggleCategory("FOOD")

            listOf(
                "   " to OptionProblem.BLANK,
                "x".repeat(SubmissionRules.MAX_OPTION_LENGTH + 1) to OptionProblem.TOO_LONG,
                "Fly\nhigh" to OptionProblem.NOT_ONE_LINE,
            ).forEach { (typed, problem) ->
                viewModel.setOptionA(typed)
                viewModel.setOptionB("Swim")
                assertEquals(problem, viewModel.state.value.optionAProblem, "\"$typed\"")

                viewModel.setOptionA("Swim")
                viewModel.setOptionB(typed)
                assertEquals(problem, viewModel.state.value.optionBProblem, "\"$typed\"")

                assertFalse(viewModel.state.value.canSubmit)
                viewModel.submit()
                testScheduler.advanceUntilIdle()
            }

            assertEquals(emptyList(), server.calls)
        }

    @Test
    fun `two options the same ignoring case cannot be sent`() =
        runTest(dispatcher) {
            val viewModel = open()
            server.calls.clear()

            viewModel.write(" Fly", "FLY ", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.sameOptions)
            assertNull(state.optionAProblem)
            assertNull(state.optionBProblem)
            assertFalse(state.canSubmit)
            assertEquals(emptyList(), server.calls)
        }

    @Test
    fun `a question under no category cannot be sent`() =
        runTest(dispatcher) {
            val viewModel = open()
            server.calls.clear()

            viewModel.write("Fly", "Swim")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            assertFalse(viewModel.state.value.canSubmit)
            assertEquals(emptyList(), server.calls)
        }

    @Test
    fun `a category tapped again is unpicked`() =
        runTest(dispatcher) {
            val viewModel = open()

            viewModel.toggleCategory("FOOD")
            viewModel.toggleCategory("ETHICS")
            viewModel.toggleCategory("FOOD")

            assertEquals(setOf("ETHICS"), viewModel.state.value.categories)
        }

    @Test
    fun `a stored question clears the form and the list is read again`() =
        runTest(dispatcher) {
            val viewModel = open()
            server.calls.clear()

            viewModel.write(" Fly ", "Swim", "SUPERPOWERS", "FOOD")
            assertTrue(viewModel.state.value.canSubmit)
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            // As typed: trimming is the server's.
            assertEquals(
                listOf("ensure", """submit " Fly "|"Swim"|[FOOD, SUPERPOWERS]""", "ensure", "mine"),
                server.calls,
            )
            val state = viewModel.state.value
            assertEquals("", state.optionA)
            assertEquals("", state.optionB)
            assertEquals(emptySet(), state.categories)
            assertTrue(state.sent)
            assertNull(state.submitFailure)
            assertNull(state.listFailure)
            assertFalse(state.isBusy)
            val listed = assertNotNull(state.submissions)
            assertEquals("Fly", listed.first().optionA)
            assertEquals(SubmissionStatus.PENDING, listed.first().status)
            assertEquals(MINE, listed.drop(1))
        }

    @Test
    fun `the next action takes the sent note down`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            viewModel.refresh()

            assertFalse(viewModel.state.value.sent)
        }

    @Test
    fun `a question the server refuses keeps the form and says so`() =
        runTest(dispatcher) {
            server.submitFailsWith = WyrException(DomainError.INVALID_SUBMISSION, "optionA has a control character")
            val viewModel = open()
            server.calls.clear()

            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(SubmitFailure(DomainError.INVALID_SUBMISSION), state.submitFailure)
            assertEquals(
                "The game can't take that question as written. Check both options.",
                failureMessage(assertNotNull(state.submitFailure)),
            )
            assertEquals("Fly", state.optionA)
            assertEquals("Swim", state.optionB)
            assertEquals(setOf("FOOD"), state.categories)
            assertFalse(state.sent)
            // After a failure too: a submission whose answer was lost may have been stored.
            assertEquals("mine", server.calls.last())
        }

    @Test
    fun `the pending limit is said plainly`() =
        runTest(dispatcher) {
            server.submitFailsWith = WyrException(DomainError.SUBMISSION_LIMIT, "20 submissions pending")
            val viewModel = open()

            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            val failure = assertNotNull(viewModel.state.value.submitFailure)
            assertEquals(SubmitFailure(DomainError.SUBMISSION_LIMIT), failure)
            assertEquals(
                "You have 20 questions waiting for review already. Send more once one is reviewed.",
                failureMessage(failure),
            )
            assertEquals("Fly", viewModel.state.value.optionA)
        }

    @Test
    fun `offline shows under the form and keeps it`() =
        runTest(dispatcher) {
            server.submitFailsWith = WyrException(DomainError.NETWORK, "connect timed out")
            val viewModel = open()

            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(SubmitFailure(DomainError.NETWORK), state.submitFailure)
            assertEquals(
                "Can't reach the game. Check your connection.",
                failureMessage(assertNotNull(state.submitFailure)),
            )
            assertEquals("Fly", state.optionA)
            assertTrue(state.canSubmit, "to send again")
        }

    @Test
    fun `a rate limit says how long to wait`() =
        runTest(dispatcher) {
            server.submitFailsWith = WyrException(DomainError.RATE_LIMITED, retryAfter = 42.seconds)
            val viewModel = open()

            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            val failure = assertNotNull(viewModel.state.value.submitFailure)
            assertEquals(SubmitFailure(DomainError.RATE_LIMITED, 42.seconds), failure)
            assertEquals("Too many tries. Wait 42 s, then try again.", failureMessage(failure))
        }

    @Test
    fun `a list that cannot be read offers to try again and a second read works`() =
        runTest(dispatcher) {
            server.mineFailsWith = DomainError.NETWORK
            val viewModel = open()

            assertNull(viewModel.state.value.submissions)
            assertEquals(SubmitFailure(DomainError.NETWORK), viewModel.state.value.listFailure)

            server.mineFailsWith = null
            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            assertEquals(MINE, viewModel.state.value.submissions)
            assertNull(viewModel.state.value.listFailure)
        }

    @Test
    fun `a stored question whose list read fails says so under the list and keeps what was shown`() =
        runTest(dispatcher) {
            val viewModel = open()
            server.mineFailsWith = DomainError.SERVER

            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.sent)
            assertNull(state.submitFailure)
            assertEquals(SubmitFailure(DomainError.SERVER), state.listFailure)
            assertEquals(MINE, state.submissions)
        }

    @Test
    fun `a refused question whose list read fails too says each under its own part`() =
        runTest(dispatcher) {
            server.submitFailsWith = WyrException(DomainError.SUBMISSION_LIMIT)
            val viewModel = open()
            server.mineFailsWith = DomainError.NETWORK

            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(SubmitFailure(DomainError.SUBMISSION_LIMIT), state.submitFailure)
            // The list is as read before, so it says it could not be read again.
            assertEquals(SubmitFailure(DomainError.NETWORK), state.listFailure)
            assertEquals(MINE, state.submissions)
        }

    @Test
    fun `a list never read whose read fails again after a refused question still offers to try again`() =
        runTest(dispatcher) {
            // Offline from the moment the tab is shown.
            server.mineFailsWith = DomainError.NETWORK
            server.submitFailsWith = WyrException(DomainError.NETWORK, "connect timed out")
            val viewModel = open()
            assertEquals(SubmitFailure(DomainError.NETWORK), viewModel.state.value.listFailure)

            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            // Nothing in flight, no list and a failure under it: the screen offers Try again there,
            // where it would otherwise wait on a read nobody makes.
            val state = viewModel.state.value
            assertFalse(state.isBusy)
            assertNull(state.submissions)
            assertEquals(SubmitFailure(DomainError.NETWORK), state.submitFailure)
            assertEquals(SubmitFailure(DomainError.NETWORK), state.listFailure)

            server.mineFailsWith = null
            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            assertEquals(MINE, viewModel.state.value.submissions)
            assertNull(viewModel.state.value.listFailure)
            assertNull(viewModel.state.value.submitFailure)
        }

    @Test
    fun `a second action while one runs is ignored`() =
        runTest(dispatcher) {
            val viewModel = open()
            server.calls.clear()
            viewModel.write("Fly", "Swim", "FOOD")

            viewModel.submit()
            viewModel.submit()
            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            assertEquals(1, server.calls.count { it.startsWith("submit") })
            assertEquals(1, server.calls.count { it == "mine" }, "only the read after the submit")
        }

    @Test
    fun `nothing is sent while the list is being read`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.write("Fly", "Swim", "FOOD")

            viewModel.refresh()
            assertFalse(viewModel.state.value.canSubmit)
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("ensure", "mine"), server.calls)
            assertTrue(viewModel.state.value.canSubmit, "once the read is done")
        }

    @Test
    fun `the form cannot change while it is being sent`() =
        runTest(dispatcher) {
            val viewModel = open()
            val answer = CompletableDeferred<Unit>()
            server.submitWaitsFor = answer
            viewModel.write("Fly", "Swim", "FOOD")

            viewModel.submit()
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value.isSubmitting)
            viewModel.setOptionA("Run")
            viewModel.setOptionB("Walk")
            viewModel.toggleCategory("ETHICS")

            assertEquals("Fly", viewModel.state.value.optionA)
            assertEquals("Swim", viewModel.state.value.optionB)
            assertEquals(setOf("FOOD"), viewModel.state.value.categories)

            answer.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals("", viewModel.state.value.optionA)
            assertFalse(viewModel.state.value.isSubmitting)
        }

    @Test
    fun `the form can change while the list is being read`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.refresh()
            viewModel.write("Fly", "Swim", "FOOD")
            testScheduler.advanceUntilIdle()

            assertEquals("Fly", viewModel.state.value.optionA)
            assertEquals(setOf("FOOD"), viewModel.state.value.categories)
        }

    /** A view model whose screen has been shown, and read the list. */
    private fun TestScope.open(): SubmitViewModel =
        viewModel().also {
            it.refresh()
            testScheduler.advanceUntilIdle()
        }

    private fun viewModel(): SubmitViewModel =
        SubmitViewModel(
            submitQuestion = SubmitQuestion(server, server),
            getMySubmissions = GetMySubmissions(server, server),
            getCategories = GetCategories(categories),
            categoryList = categories,
        )

    /** Types both options and picks exactly [categories]. */
    private fun SubmitViewModel.write(
        optionA: String,
        optionB: String,
        vararg categories: String,
    ) {
        setOptionA(optionA)
        setOptionB(optionB)
        state.value.categories.forEach(this::toggleCategory)
        categories.forEach(this::toggleCategory)
    }

    /**
     * The server and this device's session in one. Every call that reaches it is in [calls], a
     * submission with its options quoted as sent and its categories in id order.
     */
    private class FakeServer :
        SubmissionRepository,
        SessionRepository {
        val calls = mutableListOf<String>()
        var mine: List<Submission> = MINE
        var mineFailsWith: DomainError? = null
        var submitFailsWith: WyrException? = null

        /** When set, a submission waits for it before it answers. */
        var submitWaitsFor: CompletableDeferred<Unit>? = null

        override suspend fun ensure(): String {
            calls += "ensure"
            return "p1"
        }

        override suspend fun submit(
            optionA: String,
            optionB: String,
            categories: Set<String>,
        ): Submission {
            calls += "submit \"$optionA\"|\"$optionB\"|${categories.sorted()}"
            submitWaitsFor?.await()
            submitFailsWith?.let { throw it }
            val stored =
                Submission(
                    id = "q${mine.size + 1}",
                    optionA = optionA.trim(),
                    optionB = optionB.trim(),
                    categories = categories,
                    status = SubmissionStatus.PENDING,
                    rejectionReason = null,
                    submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_010L),
                )
            mine = listOf(stored) + mine
            return stored
        }

        override suspend fun mine(): List<Submission> {
            calls += "mine"
            mineFailsWith?.let { throw WyrException(it) }
            return mine
        }
    }

    /** The server's categories as the tests script them, each read counted in [reads]. */
    private class FakeCategoryRepository : CategoryRepository {
        var reads = 0

        var read: suspend () -> List<Category> = { LISTED }

        override val categories = MutableStateFlow<List<Category>>(emptyList())

        override suspend fun refresh(): List<Category> {
            reads++
            return read().also { categories.value = it }
        }

        companion object {
            val LISTED =
                listOf(
                    Category(id = "FOOD", nameSr = "Храна", nameEn = "Food"),
                    Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics"),
                    Category(id = "SUPERPOWERS", nameSr = "Супермоћи", nameEn = "Superpowers"),
                )
        }
    }

    private companion object {
        val MINE =
            listOf(
                Submission(
                    id = "q2",
                    optionA = "Tea",
                    optionB = "Coffee",
                    categories = setOf("FOOD"),
                    status = SubmissionStatus.REJECTED,
                    rejectionReason = "a duplicate",
                    submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_001L),
                ),
                Submission(
                    id = "q1",
                    optionA = "Lie",
                    optionB = "Steal",
                    categories = setOf("ETHICS"),
                    status = SubmissionStatus.PENDING,
                    rejectionReason = null,
                    submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
                ),
            )
    }
}

package io.ntole.wyr.submit

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerRepository
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.submission.OptionProblem
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.domain.submission.SubmissionRules
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.submission.SubmitQuestion
import io.ntole.wyr.language.EnglishStrings
import io.ntole.wyr.language.SerbianCyrillicStrings
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
    fun `categories that cannot be read say so and keep those read before and the points are read all the same`() =
        runTest(dispatcher) {
            val viewModel = open()
            server.calls.clear()
            categories.read = { throw WyrException(DomainError.NETWORK) }

            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(SubmitFailure(DomainError.NETWORK), state.categoriesFailure)
            assertEquals(FakeCategoryRepository.LISTED, state.categoryOptions)
            assertEquals(listOf("ensure", "stats"), server.calls)
            // Try again reads them again.
            categories.read = { FakeCategoryRepository.LISTED }
            viewModel.refresh()
            testScheduler.advanceUntilIdle()
            assertNull(viewModel.state.value.categoriesFailure)
        }

    @Test
    fun `showing the form reads the player's points`() =
        runTest(dispatcher) {
            server.points = 7

            val state = open().state.value

            assertEquals(7, state.points)
            // The session first, as every use case makes sure of it.
            assertEquals(listOf("ensure", "stats"), server.calls)
            assertNull(state.submitFailure)
            assertNull(state.pointsFailure)
            assertFalse(state.isBusy)
        }

    @Test
    fun `a question costs the points the client keeps and no more`() {
        assertEquals(1, SubmissionRules.SUBMISSION_COST)
    }

    @Test
    fun `a player with the cost can send and one with fewer points cannot`() =
        runTest(dispatcher) {
            server.points = SubmissionRules.SUBMISSION_COST
            val viewModel = open()
            viewModel.write("Fly", "Swim", "FOOD")
            assertTrue(viewModel.state.value.canSubmit)
            assertFalse(viewModel.state.value.tooFewPoints)

            server.points = SubmissionRules.SUBMISSION_COST - 1
            viewModel.refresh()
            testScheduler.advanceUntilIdle()
            server.calls.clear()

            assertTrue(viewModel.state.value.tooFewPoints)
            assertFalse(viewModel.state.value.canSubmit)
            viewModel.submit()
            testScheduler.advanceUntilIdle()
            assertEquals(emptyList(), server.calls)
        }

    @Test
    fun `nothing is sent before the points are read`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.write("Fly", "Swim", "FOOD")

            assertNull(viewModel.state.value.points)
            assertFalse(viewModel.state.value.tooFewPoints, "nothing to say before the points are known")
            assertFalse(viewModel.state.value.canSubmit)
        }

    @Test
    fun `points that cannot be read offer to try again and a second read works`() =
        runTest(dispatcher) {
            server.statsFailWith = DomainError.NETWORK
            val viewModel = open()
            viewModel.write("Fly", "Swim", "FOOD")

            assertNull(viewModel.state.value.points)
            assertEquals(SubmitFailure(DomainError.NETWORK), viewModel.state.value.pointsFailure)
            assertFalse(viewModel.state.value.canSubmit)

            server.statsFailWith = null
            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            assertEquals(5, viewModel.state.value.points)
            assertNull(viewModel.state.value.pointsFailure)
            assertTrue(viewModel.state.value.canSubmit)
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
    fun `a stored question clears the form and the points are read again`() =
        runTest(dispatcher) {
            val viewModel = open()
            server.calls.clear()

            viewModel.write(" Fly ", "Swim", "SUPERPOWERS", "FOOD")
            assertTrue(viewModel.state.value.canSubmit)
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            // As typed: trimming is the server's.
            assertEquals(
                listOf("ensure", """submit " Fly "|"Swim"|[FOOD, SUPERPOWERS]""", "ensure", "stats"),
                server.calls,
            )
            val state = viewModel.state.value
            assertEquals("", state.optionA)
            assertEquals("", state.optionB)
            assertEquals(emptySet(), state.categories)
            assertTrue(state.sent)
            assertNull(state.submitFailure)
            assertNull(state.pointsFailure)
            assertFalse(state.isBusy)
            assertEquals(4, state.points, "the server took the cost")
            assertEquals("Fly", server.stored.single().optionA)
        }

    @Test
    fun `a stored question is sent until the form goes back for it`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value.sent)

            viewModel.leftForm()

            assertFalse(viewModel.state.value.sent)
        }

    /** A question whose answer came after the player went back is not sent back later. */
    @Test
    fun `the next action takes sent down`() =
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
            val failure = assertNotNull(state.submitFailure)
            assertEquals("Not accepted. Check both options.", failureMessage(failure, ENGLISH))
            assertEquals("Fly", state.optionA)
            assertEquals("Swim", state.optionB)
            assertEquals(setOf("FOOD"), state.categories)
            assertFalse(state.sent)
            // After a failure too: a submission whose answer was lost may have been stored, and paid for.
            assertEquals("stats", server.calls.last())
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
            assertEquals("You have 20 waiting already.", failureMessage(failure, ENGLISH))
            assertEquals("Већ имаш 20 питања на чекању.", failureMessage(failure, CYRILLIC))
            assertEquals("Fly", viewModel.state.value.optionA)
        }

    @Test
    fun `a guest cannot send and is told to register first`() =
        runTest(dispatcher) {
            server.username = null
            val viewModel = open()
            viewModel.write("Fly", "Swim", "FOOD")
            server.calls.clear()

            val state = viewModel.state.value
            assertEquals(false, state.registered)
            assertTrue(state.isGuest)
            assertFalse(state.canSubmit, "only a registered player submits")
            viewModel.submit()
            testScheduler.advanceUntilIdle()
            assertEquals(emptyList(), server.calls, "nothing sent")
        }

    @Test
    fun `nothing says register before the player is read`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            assertNull(viewModel.state.value.registered)
            assertFalse(viewModel.state.value.isGuest)
        }

    /** A guest whose read was out of date: the server's refusal, and the player read again. */
    @Test
    fun `a refusal for a guest keeps the form and reads the player again`() =
        runTest(dispatcher) {
            server.submitFailsWith = WyrException(DomainError.ACCOUNT_REQUIRED, "only a registered player may submit")
            val viewModel = open()
            server.username = null

            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            val failure = assertNotNull(state.submitFailure)
            assertEquals(SubmitFailure(DomainError.ACCOUNT_REQUIRED), failure)
            assertEquals("Register to add a question.", failureMessage(failure, ENGLISH))
            assertEquals("Региструј се да додаш питање.", failureMessage(failure, CYRILLIC))
            assertEquals("Fly", state.optionA)
            assertTrue(state.isGuest)
            assertFalse(state.canSubmit)
        }

    /** The points moved since they were read: the server's refusal, and the points read again. */
    @Test
    fun `a refusal for points keeps the form and reads the points again`() =
        runTest(dispatcher) {
            server.submitFailsWith = WyrException(DomainError.NOT_ENOUGH_POINTS, "1 point needed")
            val viewModel = open()
            server.points = 0

            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            val failure = assertNotNull(state.submitFailure)
            assertEquals(SubmitFailure(DomainError.NOT_ENOUGH_POINTS), failure)
            assertEquals("Not enough points.", failureMessage(failure, ENGLISH))
            assertEquals("Fly", state.optionA)
            assertEquals(0, state.points)
            assertTrue(state.tooFewPoints)
            assertFalse(state.canSubmit)
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
            val failure = assertNotNull(state.submitFailure)
            assertEquals("No connection. Check your internet.", failureMessage(failure, ENGLISH))
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
            assertEquals("Too many tries. Wait 42 s.", failureMessage(failure, ENGLISH))
        }

    @Test
    fun `a stored question whose points read fails says so and stays sent`() =
        runTest(dispatcher) {
            val viewModel = open()
            server.statsFailWith = DomainError.SERVER

            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.sent)
            assertNull(state.submitFailure)
            assertEquals(SubmitFailure(DomainError.SERVER), state.pointsFailure)
            assertEquals(5, state.points, "what was read before")
        }

    @Test
    fun `a refused question whose points read fails too says each`() =
        runTest(dispatcher) {
            server.submitFailsWith = WyrException(DomainError.SUBMISSION_LIMIT)
            val viewModel = open()
            server.statsFailWith = DomainError.NETWORK

            viewModel.write("Fly", "Swim", "FOOD")
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(SubmitFailure(DomainError.SUBMISSION_LIMIT), state.submitFailure)
            assertEquals(SubmitFailure(DomainError.NETWORK), state.pointsFailure)
            assertFalse(state.isBusy)
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
            assertEquals(1, server.calls.count { it == "stats" }, "only the read after the submit")
        }

    @Test
    fun `nothing is sent while the points are being read`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.write("Fly", "Swim", "FOOD")
            server.calls.clear()

            viewModel.refresh()
            assertFalse(viewModel.state.value.canSubmit)
            viewModel.submit()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("ensure", "stats"), server.calls)
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
    fun `the form can change while the points are being read`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.refresh()
            viewModel.write("Fly", "Swim", "FOOD")
            testScheduler.advanceUntilIdle()

            assertEquals("Fly", viewModel.state.value.optionA)
            assertEquals(setOf("FOOD"), viewModel.state.value.categories)
        }

    /** A view model whose form has been shown, and read the points. */
    private fun TestScope.open(): SubmitViewModel =
        viewModel().also {
            it.refresh()
            testScheduler.advanceUntilIdle()
        }

    private fun viewModel(): SubmitViewModel =
        SubmitViewModel(
            submitQuestion = SubmitQuestion(server, server),
            getPlayerStats = GetPlayerStats(server, server),
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
     * submission with its options quoted as sent and its categories in id order. A question stored
     * costs [SubmissionRules.SUBMISSION_COST], as the server takes it.
     */
    private class FakeServer :
        SubmissionRepository,
        PlayerRepository,
        SessionRepository {
        val calls = mutableListOf<String>()
        val stored = mutableListOf<Submission>()
        var points = 5

        /** The player's username, a registered player's unless a test makes them a guest. */
        var username: String? = "bob"
        var statsFailWith: DomainError? = null
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
            val submission =
                Submission(
                    id = "q${stored.size + 1}",
                    optionA = optionA.trim(),
                    optionB = optionB.trim(),
                    categories = categories,
                    status = SubmissionStatus.PENDING,
                    rejectionReason = null,
                    submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_010L),
                )
            points -= SubmissionRules.SUBMISSION_COST
            stored += submission
            return submission
        }

        override suspend fun mine(): List<Submission> = error("the form lists nothing: My questions does")

        override suspend fun stats(): PlayerStats {
            calls += "stats"
            statsFailWith?.let { throw WyrException(it) }
            return PlayerStats(totalPoints = points, questionsAnswered = 0, username = username)
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
        val ENGLISH = EnglishStrings.accountScreens
        val CYRILLIC = SerbianCyrillicStrings.accountScreens
    }
}

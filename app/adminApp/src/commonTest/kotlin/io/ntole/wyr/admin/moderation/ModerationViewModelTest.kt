package io.ntole.wyr.admin.moderation

import io.ntole.wyr.admin.moderation.FakeModeration.Companion.QUEUE
import io.ntole.wyr.admin.moderation.FakeModeration.Companion.SECOND
import io.ntole.wyr.admin.moderation.FakeModeration.Companion.TOKEN
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.moderation.AddCategory
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.GetQuestions
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.moderation.RenameCategory
import io.ntole.wyr.core.domain.moderation.RestoreQuestion
import io.ntole.wyr.core.domain.moderation.RetireQuestion
import io.ntole.wyr.core.domain.submission.Submission
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
import kotlin.time.Duration.Companion.seconds

/** The token and the pending queue, driven through the ViewModel over a scripted repository. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModerationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val moderation = FakeModeration()
    private val categories = FakeCategories()

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
    fun `nothing is read when the app opens`() =
        runTest(dispatcher) {
            val state = open().state.value

            // No token has been typed, and every admin route needs one.
            assertEquals(ModerationState(), state)
            assertEquals(emptyList(), moderation.calls)
        }

    @Test
    fun `nothing is sent until what is typed can be a token`() =
        runTest(dispatcher) {
            val viewModel = open()

            listOf("", "   ", "s3cret token", "s3crét").forEach { typed ->
                viewModel.setAdminToken(typed)
                assertNull(viewModel.state.value.token, "\"$typed\"")

                viewModel.loadPending()
                viewModel.approve("q1", Screen.PENDING)
                viewModel.setReason("q1", "a duplicate")
                viewModel.reject("q1", Screen.PENDING)
                testScheduler.advanceUntilIdle()
            }

            assertEquals(emptyList(), moderation.calls)
        }

    @Test
    fun `every request carries the token as typed, trimmed`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.setAdminToken("  $TOKEN\n")

            viewModel.loadPending()
            testScheduler.advanceUntilIdle()
            viewModel.approve("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("pending", "approve q1 []", "pending"), moderation.calls)
            assertEquals(List(3) { AdminToken.of(TOKEN) }, moderation.tokens)
        }

    @Test
    fun `the token shows nowhere in the state's text`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            viewModel.setReason("q1", "a duplicate")
            moderation.reject = { _, _ -> throw WyrException(DomainError.FORBIDDEN, "wrong admin token") }

            viewModel.reject("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()

            // The state's text is what a failed assertion or a stray println would show.
            val shown = viewModel.state.value.toString()
            assertFalse(TOKEN in shown, "the token shows in $shown")
            assertEquals(TOKEN, viewModel.state.value.adminToken.text)
        }

    @Test
    fun `a new ViewModel holds no token, whatever the last one held`() =
        runTest(dispatcher) {
            openWithQueue()

            // Nothing of the last one's is stored anywhere a new one could read it back from.
            assertEquals(ModerationState(), open().state.value)
        }

    @Test
    fun `Lock forgets the token and everything read with it`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            viewModel.toggleApprovalCategory("q1", "FOOD")
            viewModel.setReason("q2", "a duplicate")
            moderation.approve = { _, _ -> throw WyrException(DomainError.ALREADY_DECIDED) }
            viewModel.approve("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()

            viewModel.lock()

            // The categories stay: the same for everybody, and read with no token.
            assertEquals(
                ModerationState(categories = CategoryList(FakeCategories.LISTED), locks = 1),
                viewModel.state.value,
            )
        }

    @Test
    fun `after Lock nothing is sent until a token is typed again`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            moderation.calls.clear()

            viewModel.lock()
            viewModel.loadPending()
            viewModel.approve("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), moderation.calls)
        }

    @Test
    fun `Lock cancels the action in flight and nothing it answers is shown`() =
        runTest(dispatcher) {
            val queue = CompletableDeferred<List<Submission>>()
            moderation.pending = { queue.await() }
            val viewModel = open()
            viewModel.setAdminToken(TOKEN)
            viewModel.loadPending()
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value.isBusy)

            viewModel.lock()
            queue.complete(QUEUE)
            testScheduler.advanceUntilIdle()

            // The categories, read before the queue, stay as Lock keeps them.
            assertEquals(
                ModerationState(categories = CategoryList(FakeCategories.LISTED), locks = 1),
                viewModel.state.value,
            )
        }

    @Test
    fun `an action started after a Lock is not cut short by the one the Lock cancelled`() =
        runTest(dispatcher) {
            val first = CompletableDeferred<List<Submission>>()
            val second = CompletableDeferred<List<Submission>>()
            val reads = ArrayDeque(listOf(first, second))
            moderation.pending = { reads.removeFirst().await() }
            val viewModel = open()
            viewModel.setAdminToken(TOKEN)
            viewModel.loadPending()
            testScheduler.advanceUntilIdle()

            viewModel.lock()
            viewModel.setAdminToken(TOKEN)
            viewModel.loadPending()
            // The cancelled read ends now, after the second one began.
            testScheduler.advanceUntilIdle()

            assertEquals(Running(Action.LOAD_PENDING), viewModel.state.value.running)
            viewModel.approve("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("pending", "pending"), moderation.calls, "nothing else goes while it runs")

            second.complete(QUEUE)
            testScheduler.advanceUntilIdle()
            assertEquals(QUEUE, viewModel.state.value.pending.submissions)
            assertFalse(viewModel.state.value.isBusy)
        }

    @Test
    fun `Load pending lists the queue`() =
        runTest(dispatcher) {
            val state = openWithQueue().state.value

            assertEquals(QUEUE, state.pending.submissions)
            assertNull(state.pending.failure)
            assertFalse(state.isBusy)
        }

    @Test
    fun `a read that fails keeps what was listed and says why`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            moderation.pending = { throw WyrException(DomainError.FORBIDDEN, "wrong admin token") }

            viewModel.loadPending()
            testScheduler.advanceUntilIdle()

            val queue = viewModel.state.value.pending
            assertEquals(QUEUE, queue.submissions)
            assertEquals(Failure.Refused(DomainError.FORBIDDEN, detail = "wrong admin token"), queue.failure)
        }

    @Test
    fun `a 429's wait stays with its failure`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.setAdminToken(TOKEN)
            moderation.pending = {
                throw WyrException(DomainError.RATE_LIMITED, "too many requests", retryAfter = 42.seconds)
            }

            viewModel.loadPending()
            testScheduler.advanceUntilIdle()

            val failure = viewModel.state.value.pending.failure
            assertEquals(Failure.Refused(DomainError.RATE_LIMITED, 42.seconds, "too many requests"), failure)
        }

    @Test
    fun `an approval with nothing picked keeps the author's categories and reads the queue again`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            moderation.pending = { listOf(SECOND) }

            viewModel.approve("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("pending", "approve q1 []", "pending"), moderation.calls)
            val queue = viewModel.state.value.pending
            assertEquals(listOf(SECOND), queue.submissions)
            // Named as the categories read with the queue name it.
            assertEquals("Approved \"Fly\" or \"Swim\" under Супермоћи.", queue.outcomes.notice)
        }

    @Test
    fun `the categories picked for a submission are the ones its approval files it under`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()

            viewModel.toggleApprovalCategory("q1", "ABSURD")
            viewModel.toggleApprovalCategory("q1", "ETHICS")
            viewModel.toggleApprovalCategory("q1", "FOOD")
            viewModel.toggleApprovalCategory("q1", "ABSURD")
            viewModel.toggleApprovalCategory("q2", "LIFESTYLE")
            viewModel.approve("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()

            // In id order, and only q1's.
            assertEquals("approve q1 [ETHICS, FOOD]", moderation.calls[1])
            assertEquals(
                "Approved \"Fly\" or \"Swim\" under Етика, Храна.",
                viewModel.state.value.pending.outcomes.notice,
            )
        }

    @Test
    fun `Reject sends nothing until the reason is one the server accepts`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            val tooLong = "x".repeat(RejectionReason.MAX_LENGTH + 1)

            listOf("", "  ", "a\nduplicate", "a duplicate", tooLong).forEach { reason ->
                viewModel.setReason("q1", reason)
                assertNull(viewModel.state.value.rejectionOf("q1"), "\"$reason\"")

                viewModel.reject("q1", Screen.PENDING)
                testScheduler.advanceUntilIdle()
            }

            assertEquals(listOf("pending"), moderation.calls)
        }

    @Test
    fun `a rejection sends the reason trimmed and reads the queue again`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()

            viewModel.setReason("q1", "  Too close to a seed ")
            viewModel.reject("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("pending", "reject q1 Too close to a seed", "pending"), moderation.calls)
            assertEquals(
                "Rejected \"Fly\" or \"Swim\": Too close to a seed",
                viewModel.state.value.pending.outcomes.notice,
            )
        }

    @Test
    fun `the queue is read again after every decision, a failed one too`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            viewModel.setReason("q1", "a duplicate")

            // All but a wrong token and the rate limit, which decide nothing (below).
            (DomainError.entries - setOf(DomainError.FORBIDDEN, DomainError.RATE_LIMITED)).forEach { error ->
                moderation.approve = { _, _ -> throw WyrException(error, "the server's word on $error") }
                moderation.reject = { _, _ -> throw WyrException(error, "the server's word on $error") }
                moderation.calls.clear()

                viewModel.approve("q1", Screen.PENDING)
                testScheduler.advanceUntilIdle()
                val approval = viewModel.state.value.pending.outcomes.failures["q1"]
                viewModel.reject("q1", Screen.PENDING)
                testScheduler.advanceUntilIdle()
                val rejection = viewModel.state.value.pending.outcomes.failures["q1"]

                // Another moderator may have decided it, or the answer been lost after it was made.
                assertEquals(listOf("approve q1 []", "pending", "reject q1 a duplicate", "pending"), moderation.calls)
                val failed =
                    ItemFailure("\"Fly\" or \"Swim\"", Failure.Refused(error, detail = "the server's word on $error"))
                assertEquals(failed, approval, "$error")
                assertEquals(failed, rejection, "$error")
            }
        }

    @Test
    fun `nothing is read again after a decision refused as a wrong token or by the rate limit`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            viewModel.loadQuestions()
            testScheduler.advanceUntilIdle()
            viewModel.setReason("q1", "a duplicate")

            listOf(DomainError.FORBIDDEN, DomainError.RATE_LIMITED).forEach { error ->
                moderation.approve = { _, _ -> throw WyrException(error, "refused") }
                moderation.reject = { _, _ -> throw WyrException(error, "refused") }
                moderation.calls.clear()

                viewModel.approve("q1", Screen.PENDING)
                testScheduler.advanceUntilIdle()
                viewModel.reject("q1", Screen.QUESTIONS)
                testScheduler.advanceUntilIdle()

                // Either is the server's answer before it decides anything, and would be its answer to a
                // read as well, which after a 403 would spend one more of the address's wrong tokens.
                assertEquals(listOf("approve q1 []", "reject q1 a duplicate"), moderation.calls, "$error")
                val refused = ItemFailure("\"Fly\" or \"Swim\"", Failure.Refused(error, detail = "refused"))
                assertEquals(refused, viewModel.state.value.pending.outcomes.failures["q1"], "$error")
                assertEquals(refused, viewModel.state.value.questions.outcomes.failures["q1"], "$error")
            }
        }

    @Test
    fun `a failed decision is kept, named by its options, once the queue no longer lists it`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            moderation.approve = { _, _ -> throw WyrException(DomainError.ALREADY_DECIDED, "already decided") }
            moderation.pending = { listOf(SECOND) }

            viewModel.approve("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()

            val queue = viewModel.state.value.pending
            assertEquals(listOf(SECOND), queue.submissions)
            assertEquals(
                mapOf(
                    "q1" to
                        ItemFailure(
                            "\"Fly\" or \"Swim\"",
                            Failure.Refused(DomainError.ALREADY_DECIDED, detail = "already decided"),
                        ),
                ),
                queue.outcomes.failures,
            )
            assertNull(queue.outcomes.notice)
        }

    @Test
    fun `a decision's failure goes with the next decision on it and with Load pending`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            moderation.approve = { _, _ -> throw WyrException(DomainError.NETWORK) }
            viewModel.approve("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()
            viewModel.approve("q2", Screen.PENDING)
            testScheduler.advanceUntilIdle()
            assertEquals(setOf("q1", "q2"), viewModel.state.value.pending.outcomes.failures.keys)

            moderation.approve = FakeModeration().approve
            viewModel.approve("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()
            assertEquals(setOf("q2"), viewModel.state.value.pending.outcomes.failures.keys)

            viewModel.loadPending()
            testScheduler.advanceUntilIdle()
            assertEquals(emptyMap(), viewModel.state.value.pending.outcomes.failures)
        }

    @Test
    fun `what was picked for a submission no longer pending goes and the rest stays`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            viewModel.toggleApprovalCategory("q1", "FOOD")
            viewModel.toggleApprovalCategory("q2", "ETHICS")
            viewModel.setReason("q2", "half typed")
            moderation.pending = { listOf(SECOND) }

            viewModel.approve("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()

            assertEquals(
                mapOf("q2" to DecisionDraft(setOf("ETHICS"), "half typed")),
                viewModel.state.value.drafts,
            )
        }

    @Test
    fun `nothing is decided while the queue is being read`() =
        runTest(dispatcher) {
            val queue = CompletableDeferred<List<Submission>>()
            moderation.pending = { queue.await() }
            val viewModel = open()
            viewModel.setAdminToken(TOKEN)

            viewModel.loadPending()
            testScheduler.advanceUntilIdle()
            assertFalse(viewModel.state.value.canSend)
            viewModel.approve("q1", Screen.PENDING)
            queue.complete(QUEUE)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("pending"), moderation.calls)
            assertTrue(viewModel.state.value.canSend)
        }

    @Test
    fun `anything else thrown is shown as a bug`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            moderation.approve = { _, _ -> error("boom") }

            viewModel.approve("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()

            assertEquals(
                Failure.Bug("IllegalStateException", "boom"),
                viewModel.state.value.pending.outcomes.failures["q1"]
                    ?.failure,
            )
        }

    @Test
    fun `cancellation is not shown as a failure`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.setAdminToken(TOKEN)
            moderation.pending = { throw CancellationException("caller went away") }

            viewModel.loadPending()
            testScheduler.advanceUntilIdle()

            assertNull(viewModel.state.value.pending.failure)
            assertFalse(viewModel.state.value.isBusy)
        }

    @Test
    fun `every Load reads the categories again before what it loads`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            assertEquals(1, categories.reads)
            assertEquals(CategoryList(FakeCategories.LISTED), viewModel.state.value.categories)

            // A moderator adds one meanwhile, here or elsewhere: the next Load names it.
            val animals = Category(id = "ANIMALS", nameSr = "Животиње", nameEn = "Animals")
            categories.read = { FakeCategories.LISTED + animals }
            viewModel.loadQuestions()
            testScheduler.advanceUntilIdle()

            assertEquals(2, categories.reads)
            assertEquals(FakeCategories.LISTED + animals, viewModel.state.value.categories.categories)
        }

    @Test
    fun `a categories read that fails keeps those read before and the queue is read all the same`() =
        runTest(dispatcher) {
            val viewModel = openWithQueue()
            categories.read = { throw WyrException(DomainError.NETWORK, "offline") }

            viewModel.loadPending()
            testScheduler.advanceUntilIdle()

            val listed = viewModel.state.value.categories
            assertEquals(FakeCategories.LISTED, listed.categories)
            assertEquals(Failure.Refused(DomainError.NETWORK, detail = "offline"), listed.failure)
            assertEquals(listOf("pending", "pending"), moderation.calls)
        }

    private fun TestScope.open(): ModerationViewModel =
        ModerationViewModel(
            getPendingSubmissions = GetPendingSubmissions(moderation),
            approveSubmission = ApproveSubmission(moderation),
            rejectSubmission = RejectSubmission(moderation),
            getQuestions = GetQuestions(moderation),
            retireQuestion = RetireQuestion(moderation),
            restoreQuestion = RestoreQuestion(moderation),
            getCategories = GetCategories(categories),
            addCategory = AddCategory(moderation),
            renameCategory = RenameCategory(moderation),
        ).also { testScheduler.advanceUntilIdle() }

    /** The app with the token typed and [QUEUE] loaded, its read the one call so far. */
    private fun TestScope.openWithQueue(): ModerationViewModel =
        open().also { viewModel ->
            viewModel.setAdminToken(TOKEN)
            viewModel.loadPending()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("pending"), moderation.calls)
        }
}

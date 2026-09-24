package io.ntole.wyr.admin.moderation

import io.ntole.wyr.admin.moderation.FakeModeration.Companion.LISTED
import io.ntole.wyr.admin.moderation.FakeModeration.Companion.TOKEN
import io.ntole.wyr.admin.moderation.FakeModeration.Companion.pageOf
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.GetQuestions
import io.ntole.wyr.core.domain.moderation.ModeratedQuestionPage
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.moderation.RestoreQuestion
import io.ntole.wyr.core.domain.moderation.RetireQuestion
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.SubmissionStatus
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

/** The list of every question: its filter, its pages, retiring and restoring, and deciding from it. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuestionListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val moderation = FakeModeration()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the list is read a page at a time until no page follows`() =
        runTest(dispatcher) {
            val viewModel = openWithList()
            assertEquals(LISTED.take(2), viewModel.state.value.questions.questions)
            assertTrue(viewModel.state.value.questions.canLoadMore)

            repeat(3) {
                viewModel.loadMore()
                testScheduler.advanceUntilIdle()
            }

            val list = viewModel.state.value.questions
            assertEquals(LISTED, list.questions)
            assertNull(list.next)
            assertFalse(list.canLoadMore)
            assertEquals(
                listOf("questions [] [] after=null", "questions [] [] after=2", "questions [] [] after=4"),
                moderation.calls,
                "nothing is asked for past the last page",
            )
        }

    @Test
    fun `Load more asks at the filter the list was read at, with the cursor the page before gave`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.setAdminToken(TOKEN)
            viewModel.toggleStatusFilter(SubmissionStatus.RETIRED)
            viewModel.toggleStatusFilter(SubmissionStatus.APPROVED)
            viewModel.toggleCategoryFilter(Category.RANDOM)
            viewModel.toggleCategoryFilter(Category.FOOD)

            viewModel.loadQuestions()
            testScheduler.advanceUntilIdle()
            viewModel.loadMore()
            testScheduler.advanceUntilIdle()

            // In declaration order, whatever order they were picked in.
            assertEquals(
                listOf(
                    "questions [APPROVED, RETIRED] [FOOD, RANDOM] after=null",
                    "questions [APPROVED, RETIRED] [FOOD, RANDOM] after=2",
                ),
                moderation.calls,
            )
        }

    @Test
    fun `changing the filter drops what was read at the one before`() =
        runTest(dispatcher) {
            val viewModel = openWithList()
            moderation.retire = { throw WyrException(DomainError.WRONG_STATUS) }
            viewModel.askToRetire("seed-1")
            viewModel.confirmRetire()
            testScheduler.advanceUntilIdle()

            viewModel.toggleStatusFilter(SubmissionStatus.RETIRED)

            assertEquals(
                QuestionList(filter = QuestionFilter(statuses = setOf(SubmissionStatus.RETIRED))),
                viewModel.state.value.questions,
            )
        }

    @Test
    fun `retired is a status the list can be filtered by, and OTHER is none`() =
        runTest(dispatcher) {
            val viewModel = open()

            viewModel.toggleStatusFilter(SubmissionStatus.OTHER)
            viewModel.toggleCategoryFilter(Category.OTHER)

            assertEquals(QuestionFilter(), viewModel.state.value.questions.filter)
            assertEquals(
                listOf(
                    SubmissionStatus.PENDING,
                    SubmissionStatus.APPROVED,
                    SubmissionStatus.REJECTED,
                    SubmissionStatus.RETIRED,
                ),
                LISTABLE_STATUSES,
            )
        }

    @Test
    fun `Show everything lists every question again`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.toggleStatusFilter(SubmissionStatus.PENDING)
            viewModel.toggleCategoryFilter(Category.ETHICS)

            viewModel.clearFilter()

            assertEquals(QuestionFilter(), viewModel.state.value.questions.filter)
        }

    @Test
    fun `the filter does not change while a read for it is in flight`() =
        runTest(dispatcher) {
            val page = CompletableDeferred<ModeratedQuestionPage>()
            moderation.questions = { _, _ -> page.await() }
            val viewModel = open()
            viewModel.setAdminToken(TOKEN)
            viewModel.loadQuestions()
            testScheduler.advanceUntilIdle()

            viewModel.toggleStatusFilter(SubmissionStatus.PENDING)
            page.complete(pageOf(LISTED, null))
            testScheduler.advanceUntilIdle()

            val list = viewModel.state.value.questions
            assertEquals(QuestionFilter(), list.filter)
            assertEquals(LISTED.take(2), list.questions)
        }

    @Test
    fun `Retire asks first and sends nothing until it is confirmed`() =
        runTest(dispatcher) {
            val viewModel = openWithList()

            viewModel.askToRetire("seed-1")
            testScheduler.advanceUntilIdle()
            assertEquals("seed-1", viewModel.state.value.retiring)
            viewModel.cancelRetire()
            testScheduler.advanceUntilIdle()
            assertNull(viewModel.state.value.retiring)
            assertEquals(listOf("questions [] [] after=null"), moderation.calls)

            viewModel.askToRetire("seed-1")
            viewModel.confirmRetire()
            testScheduler.advanceUntilIdle()

            assertNull(viewModel.state.value.retiring)
            assertEquals(
                listOf("questions [] [] after=null", "retire seed-1", "questions [] [] after=null"),
                moderation.calls,
            )
            assertEquals(
                "Retired \"Cats\" or \"Dogs\": served to nobody until restored.",
                viewModel.state.value.questions.outcomes.notice,
            )
        }

    @Test
    fun `only an approved question the list shows can be asked about, and only with a token`() =
        runTest(dispatcher) {
            val viewModel = openWithList()

            listOf("q1", "q3", "q4", "not-listed").forEach { id ->
                viewModel.askToRetire(id)
                assertNull(viewModel.state.value.retiring, id)
            }
            viewModel.setAdminToken(" ")
            viewModel.askToRetire("seed-1")
            assertNull(viewModel.state.value.retiring)
            viewModel.confirmRetire()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("questions [] [] after=null"), moderation.calls)
        }

    @Test
    fun `an action on a question reads the list again as deep as it was shown`() =
        runTest(dispatcher) {
            val viewModel = openWithList()
            viewModel.loadMore()
            testScheduler.advanceUntilIdle()
            val retired = LISTED.map { if (it.id == "seed-1") moderation.retire.invoke(it.id) else it }
            moderation.calls.clear()
            moderation.questions = { _, after -> pageOf(retired, after) }

            viewModel.askToRetire("seed-1")
            viewModel.confirmRetire()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf("retire seed-1", "questions [] [] after=null", "questions [] [] after=2"),
                moderation.calls,
            )
            val list = viewModel.state.value.questions
            assertEquals(retired.take(4), list.questions, "the rows as the server holds them now")
            assertEquals(QuestionCursor("4"), list.next)
        }

    @Test
    fun `Restore sends at once and reads the list again`() =
        runTest(dispatcher) {
            val viewModel = openWithList()
            viewModel.loadMore()
            testScheduler.advanceUntilIdle()
            moderation.calls.clear()

            viewModel.restore("q3")
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf("restore q3", "questions [] [] after=null", "questions [] [] after=2"),
                moderation.calls,
            )
            assertEquals(
                "Restored \"Sea\" or \"Mountains\": served again.",
                viewModel.state.value.questions.outcomes.notice,
            )
        }

    @Test
    fun `a retirement another moderator beat shows under its question and the list is read again`() =
        runTest(dispatcher) {
            val viewModel = openWithList()
            moderation.retire = { throw WyrException(DomainError.WRONG_STATUS, "not approved") }

            viewModel.askToRetire("seed-1")
            viewModel.confirmRetire()
            testScheduler.advanceUntilIdle()

            val list = viewModel.state.value.questions
            assertEquals(
                mapOf(
                    "seed-1" to
                        ItemFailure(
                            "\"Cats\" or \"Dogs\"",
                            Failure.Refused(DomainError.WRONG_STATUS, detail = "not approved"),
                        ),
                ),
                list.outcomes.failures,
            )
            assertNull(list.outcomes.notice)
            assertEquals("questions [] [] after=null", moderation.calls.last())
            assertEquals(
                emptyMap(),
                viewModel.state.value.pending.outcomes.failures,
                "the queue's own outcomes are its own",
            )
        }

    @Test
    fun `a retirement or restoration refused as a wrong token or by the rate limit reads nothing again`() =
        runTest(dispatcher) {
            val viewModel = openWithList()
            viewModel.loadMore()
            testScheduler.advanceUntilIdle()

            listOf(DomainError.FORBIDDEN, DomainError.RATE_LIMITED).forEach { error ->
                moderation.retire = { throw WyrException(error, "refused") }
                moderation.restore = { throw WyrException(error, "refused") }
                moderation.calls.clear()

                viewModel.askToRetire("seed-1")
                viewModel.confirmRetire()
                testScheduler.advanceUntilIdle()
                viewModel.restore("q3")
                testScheduler.advanceUntilIdle()

                // Neither moved anything, and a read now would be refused the same way.
                assertEquals(listOf("retire seed-1", "restore q3"), moderation.calls, "$error")
                val failures = viewModel.state.value.questions.outcomes.failures
                assertEquals(Failure.Refused(error, detail = "refused"), failures["seed-1"]?.failure, "$error")
                assertEquals(Failure.Refused(error, detail = "refused"), failures["q3"]?.failure, "$error")
            }
        }

    @Test
    fun `a pending question in the list is decided as in the queue, and both are read again`() =
        runTest(dispatcher) {
            val viewModel = openWithList()
            viewModel.loadPending()
            testScheduler.advanceUntilIdle()
            viewModel.toggleApprovalCategory("q1", Category.ETHICS)
            moderation.calls.clear()

            viewModel.approve("q1", Screen.QUESTIONS)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("approve q1 [ETHICS]", "pending", "questions [] [] after=null"), moderation.calls)
            val state = viewModel.state.value
            assertEquals("Approved \"Fly\" or \"Swim\" under ETHICS.", state.questions.outcomes.notice)
            assertNull(state.pending.outcomes.notice, "the list's notice is the list's")
        }

    @Test
    fun `a rejection from the list keeps its failure under the question in the list`() =
        runTest(dispatcher) {
            val viewModel = openWithList()
            viewModel.setReason("q1", "a duplicate")
            moderation.reject = { _, _ -> throw WyrException(DomainError.ALREADY_DECIDED, "decided") }

            viewModel.reject("q1", Screen.QUESTIONS)
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(
                ItemFailure("\"Fly\" or \"Swim\"", Failure.Refused(DomainError.ALREADY_DECIDED, detail = "decided")),
                state.questions.outcomes.failures["q1"],
            )
            assertEquals(emptyMap(), state.pending.outcomes.failures)
        }

    @Test
    fun `a decision from the queue reads the list again only once the list was read`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.setAdminToken(TOKEN)
            viewModel.loadPending()
            testScheduler.advanceUntilIdle()

            viewModel.approve("q1", Screen.PENDING)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("pending", "approve q1 []", "pending"), moderation.calls)

            viewModel.loadQuestions()
            testScheduler.advanceUntilIdle()
            moderation.calls.clear()
            viewModel.approve("q2", Screen.PENDING)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("approve q2 []", "pending", "questions [] [] after=null"), moderation.calls)
        }

    @Test
    fun `what was picked for a question pending in the list alone stays`() =
        runTest(dispatcher) {
            val viewModel = openWithList()
            moderation.pending = { emptyList() }
            viewModel.toggleApprovalCategory("q1", Category.FOOD)
            viewModel.loadPending()
            testScheduler.advanceUntilIdle()

            assertEquals(mapOf("q1" to DecisionDraft(setOf(Category.FOOD))), viewModel.state.value.drafts)

            viewModel.toggleStatusFilter(SubmissionStatus.APPROVED)
            assertEquals(emptyMap(), viewModel.state.value.drafts, "pending nowhere listed any more")
        }

    @Test
    fun `a read of the list that fails keeps what was listed and says why`() =
        runTest(dispatcher) {
            val viewModel = openWithList()
            moderation.questions = { _, _ -> throw WyrException(DomainError.NETWORK, "timed out") }

            viewModel.loadMore()
            testScheduler.advanceUntilIdle()

            val list = viewModel.state.value.questions
            assertEquals(LISTED.take(2), list.questions)
            assertEquals(QuestionCursor("2"), list.next)
            assertEquals(Failure.Refused(DomainError.NETWORK, detail = "timed out"), list.failure)
        }

    @Test
    fun `an empty page that claims another ends the read again`() =
        runTest(dispatcher) {
            val viewModel = openWithList()
            viewModel.loadMore()
            testScheduler.advanceUntilIdle()
            var reads = 0
            moderation.questions = { _, _ ->
                check(++reads < 10) { "the read did not stop" }
                ModeratedQuestionPage(emptyList(), QuestionCursor("more"))
            }

            viewModel.restore("q3")
            testScheduler.advanceUntilIdle()

            assertEquals(1, reads)
            assertEquals(emptyList(), viewModel.state.value.questions.questions)
        }

    @Test
    fun `Load starts the list's failures afresh`() =
        runTest(dispatcher) {
            val viewModel = openWithList()
            moderation.retire = { throw WyrException(DomainError.WRONG_STATUS) }
            viewModel.askToRetire("seed-1")
            viewModel.confirmRetire()
            testScheduler.advanceUntilIdle()
            assertEquals(setOf("seed-1"), viewModel.state.value.questions.outcomes.failures.keys)

            viewModel.loadQuestions()
            testScheduler.advanceUntilIdle()

            assertEquals(Outcomes(), viewModel.state.value.questions.outcomes)
        }

    @Test
    fun `Lock forgets the list and the question waiting to be retired, and keeps the filter`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.setAdminToken(TOKEN)
            viewModel.toggleCategoryFilter(Category.RANDOM)
            viewModel.loadQuestions()
            viewModel.loadPending()
            testScheduler.advanceUntilIdle()
            viewModel.askToRetire("seed-1")

            viewModel.lock()

            val filter = QuestionFilter(categories = setOf(Category.RANDOM))
            assertEquals(ModerationState(questions = QuestionList(filter = filter), locks = 1), viewModel.state.value)
        }

    private fun TestScope.open(): ModerationViewModel =
        ModerationViewModel(
            getPendingSubmissions = GetPendingSubmissions(moderation),
            approveSubmission = ApproveSubmission(moderation),
            rejectSubmission = RejectSubmission(moderation),
            getQuestions = GetQuestions(moderation),
            retireQuestion = RetireQuestion(moderation),
            restoreQuestion = RestoreQuestion(moderation),
        ).also { testScheduler.advanceUntilIdle() }

    /** The app with the token typed and the list's first page read, the one call so far. */
    private fun TestScope.openWithList(): ModerationViewModel =
        open().also { viewModel ->
            viewModel.setAdminToken(TOKEN)
            viewModel.loadQuestions()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("questions [] [] after=null"), moderation.calls)
        }
}

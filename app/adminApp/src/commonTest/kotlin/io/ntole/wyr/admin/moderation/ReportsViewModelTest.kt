package io.ntole.wyr.admin.moderation

import io.ntole.wyr.admin.moderation.FakeModeration.Companion.LISTED
import io.ntole.wyr.admin.moderation.FakeModeration.Companion.REPORTED
import io.ntole.wyr.admin.moderation.FakeModeration.Companion.TOKEN
import io.ntole.wyr.admin.moderation.FakeModeration.Companion.reported
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.moderation.ReportReason
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
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** The Reports tab: reading the reported questions, dismissing their reports, and retiring and restoring from it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {
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
    fun `nothing is read until Load reports and nothing without a token`() =
        runTest(dispatcher) {
            val viewModel = open()

            viewModel.loadReports()
            viewModel.dismiss("q5")
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), moderation.calls)
            assertNull(viewModel.state.value.reports.reports)
        }

    @Test
    fun `Load reports reads the categories and then the reports as the server lists them`() =
        runTest(dispatcher) {
            val viewModel = openWithReports()

            assertEquals(1, categories.reads)
            assertEquals(ReportList(reports = REPORTED), viewModel.state.value.reports)
            assertEquals(List(1) { TOKEN }, moderation.tokens.map { it.value })
        }

    @Test
    fun `a read that fails keeps what was listed and says why`() =
        runTest(dispatcher) {
            val viewModel = openWithReports()
            moderation.reports = { throw WyrException(DomainError.NETWORK, "offline") }

            viewModel.loadReports()
            testScheduler.advanceUntilIdle()

            val reports = viewModel.state.value.reports
            assertEquals(REPORTED, reports.reports)
            assertEquals(Failure.Refused(DomainError.NETWORK, detail = "offline"), reports.failure)
        }

    @Test
    fun `Dismiss clears a question's reports and the reports are read again`() =
        runTest(dispatcher) {
            val viewModel = openWithReports()
            moderation.reports = { REPORTED.drop(1) }

            viewModel.dismiss("q5")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("reports", "dismissReports q5", "reports"), moderation.calls)
            val reports = viewModel.state.value.reports
            assertEquals(REPORTED.drop(1), reports.reports)
            assertEquals(
                "Dismissed the reports of \"Early\" or \"Late\": it stays as it stands.",
                reports.outcomes.notice,
            )
        }

    @Test
    fun `a dismissal that failed shows under its question and the reports are read again`() =
        runTest(dispatcher) {
            val viewModel = openWithReports()
            moderation.dismissReports = { throw WyrException(DomainError.NETWORK, "lost") }

            viewModel.dismiss("q5")
            testScheduler.advanceUntilIdle()

            // Its answer lost, it may have been made: only a read says whether.
            assertEquals(listOf("reports", "dismissReports q5", "reports"), moderation.calls)
            assertEquals(
                ItemFailure("\"Early\" or \"Late\"", Failure.Refused(DomainError.NETWORK, detail = "lost")),
                viewModel.state.value.reports.outcomes.failures["q5"],
            )
            assertNull(viewModel.state.value.reports.outcomes.notice)
        }

    @Test
    fun `nothing is read again after a dismissal refused as a wrong token or by the rate limit`() =
        runTest(dispatcher) {
            val viewModel = openWithReports()

            listOf(
                WyrException(DomainError.FORBIDDEN, "wrong admin token"),
                WyrException(DomainError.RATE_LIMITED, "slow down", retryAfter = 30.seconds),
            ).forEach { refusal ->
                moderation.dismissReports = { throw refusal }
                moderation.calls.clear()

                viewModel.dismiss("q5")
                testScheduler.advanceUntilIdle()

                assertEquals(listOf("dismissReports q5"), moderation.calls, refusal.error.name)
            }
        }

    @Test
    fun `Retire from the reports asks first and puts the answer in its row and in the list's`() =
        runTest(dispatcher) {
            val viewModel = openWithReports()
            viewModel.loadQuestions()
            testScheduler.advanceUntilIdle()
            moderation.calls.clear()

            viewModel.askToRetire("seed-1", Screen.REPORTS)
            assertEquals(Retiring("seed-1", Screen.REPORTS), viewModel.state.value.retiring)
            assertEquals(emptyList(), moderation.calls, "nothing is sent until it is confirmed")
            viewModel.confirmRetire()
            testScheduler.advanceUntilIdle()

            // The answer is what a read would show, so nothing is read again.
            assertEquals(listOf("retire seed-1"), moderation.calls)
            val retired = moderation.retire.invoke("seed-1")
            val state = viewModel.state.value
            assertEquals(
                REPORTED.map { if (it.question.id == "seed-1") it.copy(question = retired) else it },
                state.reports.reports,
            )
            assertEquals(LISTED.take(2).map { if (it.id == "seed-1") retired else it }, state.questions.questions)
            assertEquals(
                "Retired \"Cats\" or \"Dogs\": served to nobody until restored.",
                state.reports.outcomes.notice,
            )
            assertNull(state.questions.outcomes.notice, "said where it was done")
        }

    @Test
    fun `only an approved reported question can be asked to retire`() =
        runTest(dispatcher) {
            moderation.reports = { listOf(reported("q3"), reported("q5")) }
            val viewModel = openWithReports()

            viewModel.askToRetire("q3", Screen.REPORTS)
            assertNull(viewModel.state.value.retiring, "retired already")
            viewModel.askToRetire("q1", Screen.REPORTS)
            assertNull(viewModel.state.value.retiring, "not reported")
            viewModel.askToRetire("q5", Screen.QUESTIONS)
            assertNull(viewModel.state.value.retiring, "not in the list, which has not been read")
        }

    @Test
    fun `Restore from the reports sends at once and puts the answer in its row`() =
        runTest(dispatcher) {
            moderation.reports = { listOf(reported("q3")) }
            val viewModel = openWithReports()

            viewModel.restore("q3", Screen.REPORTS)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("reports", "restore q3"), moderation.calls)
            val reports = viewModel.state.value.reports
            assertEquals(
                SubmissionStatus.APPROVED,
                reports.reports
                    ?.single()
                    ?.question
                    ?.status,
            )
            assertEquals("Restored \"Sea\" or \"Mountains\": served again.", reports.outcomes.notice)
        }

    @Test
    fun `a move from the reports that failed shows under its question and the reports are read again`() =
        runTest(dispatcher) {
            val viewModel = openWithReports()
            moderation.retire = { throw WyrException(DomainError.WRONG_STATUS, "not approved") }

            viewModel.askToRetire("q5", Screen.REPORTS)
            viewModel.confirmRetire()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("reports", "retire q5", "reports"), moderation.calls)
            assertEquals(
                ItemFailure(
                    "\"Early\" or \"Late\"",
                    Failure.Refused(DomainError.WRONG_STATUS, detail = "not approved"),
                ),
                viewModel.state.value.reports.outcomes.failures["q5"],
            )
        }

    @Test
    fun `a move from the list puts its answer in a reported question's row too`() =
        runTest(dispatcher) {
            val viewModel = openWithReports()
            viewModel.loadQuestions()
            testScheduler.advanceUntilIdle()
            moderation.calls.clear()

            viewModel.askToRetire("seed-1", Screen.QUESTIONS)
            viewModel.confirmRetire()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("retire seed-1"), moderation.calls)
            val row =
                viewModel.state.value.reports.reports
                    ?.single { it.question.id == "seed-1" }
            assertEquals(SubmissionStatus.RETIRED, row?.question?.status)
            assertEquals(mapOf(ReportReason.NOT_A_CHOICE to 1), row?.reasons, "its reports as they were")
        }

    @Test
    fun `one action at a time`() =
        runTest(dispatcher) {
            val viewModel = openWithReports()
            val answer = CompletableDeferred<Unit>()
            moderation.dismissReports = { answer.await() }

            viewModel.dismiss("q5")
            testScheduler.advanceUntilIdle()
            viewModel.dismiss("seed-1")
            viewModel.loadReports()
            testScheduler.advanceUntilIdle()
            assertEquals(Running(Action.DISMISS_REPORTS, "q5"), viewModel.state.value.running)
            answer.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("reports", "dismissReports q5", "reports"), moderation.calls)
        }

    @Test
    fun `Lock forgets the reports read with the token`() =
        runTest(dispatcher) {
            val viewModel = openWithReports()

            viewModel.lock()

            assertEquals(ReportList(), viewModel.state.value.reports)
        }

    private fun TestScope.open(): ModerationViewModel =
        moderationViewModelOver(moderation, categories).also { testScheduler.advanceUntilIdle() }

    /** The app with the token typed and the reports read, the one call so far. */
    private fun TestScope.openWithReports(): ModerationViewModel =
        open().also { viewModel ->
            viewModel.setAdminToken(TOKEN)
            viewModel.loadReports()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("reports"), moderation.calls)
        }
}

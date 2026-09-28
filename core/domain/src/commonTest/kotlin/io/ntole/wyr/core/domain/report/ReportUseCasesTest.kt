package io.ntole.wyr.core.domain.report

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The Play screen's menu (CLAUDE.md §8d, *Reports*): each ensures a session, then sends. */
class ReportUseCasesTest {
    private val calls = mutableListOf<String>()
    private val reports = RecordingReports(calls)
    private val questions = RecordingQuestions(calls)
    private val session = RecordingSessions(calls)

    @Test
    fun `a report ensures the session and then sends the reason`() =
        runTest {
            ReportQuestion(reports, session)("q1", ReportReason.REAL_PERSON)

            assertEquals(listOf("ensure", "report q1 REAL_PERSON"), calls)
        }

    @Test
    fun `hiding a question ensures the session and then sends it`() =
        runTest {
            HideQuestion(reports, session)("q1")

            assertEquals(listOf("ensure", "hide question q1"), calls)
        }

    /** The queue may hold the author's other questions, fetched before the hide. */
    @Test
    fun `hiding an author drops the queue once the server has it`() =
        runTest {
            HideAuthor(reports, questions, session)("q1")

            assertEquals(listOf("ensure", "hide author q1", "reset"), calls)
        }

    @Test
    fun `an author's hide that failed keeps the queue`() =
        runTest {
            reports.failure = DomainError.NETWORK

            val failure = assertFailsWith<WyrException> { HideAuthor(reports, questions, session)("q1") }

            assertEquals(DomainError.NETWORK, failure.error)
            assertEquals(listOf("ensure", "hide author q1"), calls)
        }

    private class RecordingReports(
        private val calls: MutableList<String>,
    ) : ReportRepository {
        var failure: DomainError? = null

        override suspend fun report(
            questionId: String,
            reason: ReportReason,
        ) = record("report $questionId $reason")

        override suspend fun hideQuestion(questionId: String) = record("hide question $questionId")

        override suspend fun hideAuthor(questionId: String) = record("hide author $questionId")

        private fun record(call: String) {
            calls += call
            failure?.let { throw WyrException(it) }
        }
    }

    private class RecordingQuestions(
        private val calls: MutableList<String>,
    ) : QuestionRepository {
        override val categories: StateFlow<Set<String>> = MutableStateFlow(emptySet())

        override suspend fun next(): Question = error("not asked")

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<String>) = Unit

        override suspend fun skip(questionId: String) = Unit

        override suspend fun reset() {
            calls += "reset"
        }
    }

    private class RecordingSessions(
        private val calls: MutableList<String>,
    ) : SessionRepository {
        override suspend fun ensure(): String {
            calls += "ensure"
            return "p1"
        }
    }
}

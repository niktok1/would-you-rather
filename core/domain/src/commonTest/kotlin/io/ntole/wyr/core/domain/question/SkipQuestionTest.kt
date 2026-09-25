package io.ntole.wyr.core.domain.question

import io.ntole.wyr.core.domain.session.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SkipQuestionTest {
    private val calls = mutableListOf<String>()

    @Test
    fun `the session is ensured before the question is skipped`() =
        runTest {
            val skipQuestion = SkipQuestion(RecordingQuestions(calls), RecordingSessions(calls))

            skipQuestion("q1")

            assertEquals(listOf("ensure", "skip q1"), calls)
        }

    private class RecordingQuestions(
        private val calls: MutableList<String>,
    ) : QuestionRepository {
        override val categories: StateFlow<Set<Category>> = MutableStateFlow(emptySet())

        override suspend fun next(): Question = error("a skip fetches nothing")

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<Category>) = Unit

        override suspend fun skip(questionId: String) {
            calls += "skip $questionId"
        }

        override suspend fun reset() = Unit
    }

    private class RecordingSessions(
        private val calls: MutableList<String>,
    ) : SessionRepository {
        override suspend fun ensure(): String {
            calls += "ensure"
            return "p1"
        }

        override suspend fun currentPlayerId(): String = "p1"

        override suspend fun clear() = Unit

        override suspend fun clearKeepingSecret() = Unit
    }
}

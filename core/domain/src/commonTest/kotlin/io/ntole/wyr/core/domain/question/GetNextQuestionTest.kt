package io.ntole.wyr.core.domain.question

import io.ntole.wyr.core.domain.session.SessionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GetNextQuestionTest {
    private val calls = mutableListOf<String>()

    @Test
    fun `the session is ensured before a question is asked for`() =
        runTest {
            val getNextQuestion = GetNextQuestion(RecordingQuestions(calls), RecordingSessions(calls))

            assertEquals(QUESTION, getNextQuestion())
            assertEquals(listOf("ensure", "next"), calls)
        }

    private class RecordingQuestions(
        private val calls: MutableList<String>,
    ) : QuestionRepository {
        override suspend fun next(): Question {
            calls += "next"
            return QUESTION
        }

        override suspend fun prefetch() = Unit

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
    }

    private companion object {
        val QUESTION = Question(id = "q1", optionA = "Fly", optionB = "Turn invisible", category = Category.SUPERPOWERS)
    }
}

package io.ntole.wyr.core.data.cache

import io.ntole.wyr.core.domain.question.Question
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InMemoryQuestionCacheTest {
    private val cache = InMemoryQuestionCache()

    @Test
    fun `a question already queued is not queued twice`() =
        runTest {
            cache.put(questions("q1", "q2"))
            cache.put(questions("q2", "q3", "q3"))

            assertEquals(listOf("q1", "q2", "q3"), drain())
        }

    @Test
    fun `a question taken earlier is queued again`() =
        runTest {
            // What the endless feed needs: once every question is answered, they all come back.
            cache.put(questions("q1", "q2"))
            drain()

            cache.put(questions("q2", "q1"))

            assertEquals(listOf("q2", "q1"), drain())
        }

    private suspend fun drain(): List<String> {
        val ids = mutableListOf<String>()
        while (true) ids += cache.takeNext()?.id ?: return ids
    }

    private fun questions(vararg ids: String): List<Question> =
        ids.map { id -> Question(id = id, optionA = "$id-a", optionB = "$id-b", categories = setOf("FOOD")) }
}

package io.ntole.wyr.core.data.question

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.cache.InMemoryQuestionCache
import io.ntole.wyr.core.data.respondJson
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.api.QuestionApi
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultQuestionRepositoryTest {
    private val cache = InMemoryQuestionCache()

    @Test
    fun `a reset drops the queue and starts again from the first page`() =
        runTest {
            val engine =
                MockEngine { request ->
                    val page = if (request.cursor() == null) FIRST_PAGE else SECOND_PAGE
                    respondJson(WyrJson.encodeToString(page))
                }
            val repository = repositoryOver(engine)
            assertEquals("q1", repository.next().id)

            repository.reset()

            assertEquals(0, cache.count())
            // Without the reset this is q2 from the queue, or q4 from the cursor's page.
            assertEquals("q1", repository.next().id)
            assertEquals(listOf<String?>(null, null), engine.requestHistory.map { it.cursor() })
        }

    @Test
    fun `a refill in flight when the reset lands cannot put old questions back`() =
        runTest {
            val pageRequested = CompletableDeferred<Unit>()
            val answerPage = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    pageRequested.complete(Unit)
                    answerPage.await()
                    respondJson(WyrJson.encodeToString(FIRST_PAGE))
                }
            val repository = repositoryOver(engine)

            val refill = async { repository.prefetch() }
            pageRequested.await()
            // Runs until it has to wait: the refill holds the lock while its page is in flight.
            val reset = launch(start = CoroutineStart.UNDISPATCHED) { repository.reset() }
            answerPage.complete(Unit)
            refill.await()
            reset.join()

            assertEquals(0, cache.count())
            repository.next()
            assertNull(engine.requestHistory.last().cursor(), "the old page's cursor survived the reset")
        }

    private fun repositoryOver(engine: MockEngine): DefaultQuestionRepository =
        DefaultQuestionRepository(
            QuestionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine)),
            cache,
        )

    private fun HttpRequestData.cursor(): String? = url.parameters[WyrApi.Query.CURSOR]

    private companion object {
        val FIRST_PAGE = QuestionPageDto(questions = questions("q1", "q2", "q3"), nextCursor = "page2")
        val SECOND_PAGE = QuestionPageDto(questions = questions("q4", "q5", "q6"))

        fun questions(vararg ids: String): List<QuestionDto> =
            ids.map { id ->
                QuestionDto(id = id, optionA = "$id-a", optionB = "$id-b", category = QuestionCategory.FOOD)
            }
    }
}

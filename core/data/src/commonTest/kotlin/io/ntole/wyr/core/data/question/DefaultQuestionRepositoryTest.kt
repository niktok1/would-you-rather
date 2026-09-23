package io.ntole.wyr.core.data.question

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.cache.InMemoryQuestionCache
import io.ntole.wyr.core.data.respondJson
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.api.AuthApi
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

class DefaultQuestionRepositoryTest {
    private val cache = InMemoryQuestionCache()

    @Test
    fun `a reset drops the queue so the next question comes from a fresh batch`() =
        runTest {
            val engine = MockEngine { respondJson(WyrJson.encodeToString(BATCH)) }
            val repository = repositoryOver(engine)
            assertEquals("q1", repository.next().id)

            repository.reset()

            assertEquals(0, cache.count())
            // Without the reset this is q2, still queued from the first batch.
            assertEquals("q1", repository.next().id)
            assertEquals(2, engine.requestHistory.size)
        }

    @Test
    fun `a refill in flight when the reset lands cannot put old questions back`() =
        runTest {
            val batchRequested = CompletableDeferred<Unit>()
            val answerBatch = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    batchRequested.complete(Unit)
                    answerBatch.await()
                    respondJson(WyrJson.encodeToString(BATCH))
                }
            val repository = repositoryOver(engine)

            val refill = async { repository.prefetch() }
            batchRequested.await()
            // Runs until it has to wait: the refill holds the lock while its batch is in flight.
            val reset = launch(start = CoroutineStart.UNDISPATCHED) { repository.reset() }
            answerBatch.complete(Unit)
            refill.await()
            reset.join()

            assertEquals(0, cache.count())
        }

    @Test
    fun `a fetch with no session stored mints a guest and is retried as it`() =
        runTest {
            // The feed is per player, so a first launch's first question needs a session too.
            val server = FakeServer()
            val repository = repositoryOver(server.engine, store = storeHolding(null))

            assertEquals("q1", repository.next().id)

            assertEquals(1, server.guestsMinted)
            assertEquals(listOf(null, "Bearer access-guest1"), server.feedsSentAs)
        }

    private fun repositoryOver(
        engine: HttpClientEngine,
        store: SessionStore = storeHolding(session("a")),
    ): DefaultQuestionRepository {
        val client = WyrHttpClient.create(BASE_URL, store, engine)
        return DefaultQuestionRepository(QuestionApi(client), DefaultSessionRepository(AuthApi(client), store), cache)
    }

    private companion object {
        val BATCH =
            QuestionPageDto(
                questions =
                    listOf("q1", "q2", "q3").map { id ->
                        QuestionDto(id = id, optionA = "$id-a", optionB = "$id-b", category = QuestionCategory.FOOD)
                    },
            )
    }
}

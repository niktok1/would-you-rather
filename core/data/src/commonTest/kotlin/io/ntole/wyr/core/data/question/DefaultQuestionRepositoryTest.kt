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
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.GetNextQuestion
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultQuestionRepositoryTest {
    private val cache = InMemoryQuestionCache()

    @Test
    fun `a question still queued is not queued again by the next batch`() =
        runTest {
            // Unanswered questions stay in the feed, so the second batch repeats what is queued.
            val repository = repositoryOver(feedOf(batchOf("q1", "q2", "q3"), batchOf("q2", "q3", "q4")))
            assertEquals("q1", repository.next().id)

            repository.prefetch()

            assertEquals(listOf("q2", "q3", "q4"), List(cache.count()) { repository.next().id })
        }

    @Test
    fun `a refill before the player answers cannot queue the question on screen`() =
        runTest {
            val repository = repositoryOver(feedOf(batchOf("q1"), batchOf("q1", "q2")))
            assertEquals("q1", repository.next().id)

            // Still unanswered, so the feed serves it again while it is on screen.
            repository.prefetch()

            assertEquals(1, cache.count())
            assertEquals("q2", repository.next().id)
        }

    @Test
    fun `a question handed out while a refill is in flight is not queued by it`() =
        runTest {
            val secondBatchRequested = CompletableDeferred<Unit>()
            val answerSecondBatch = CompletableDeferred<Unit>()
            var requests = 0
            val engine =
                MockEngine {
                    val request = requests++
                    if (request == 1) {
                        secondBatchRequested.complete(Unit)
                        answerSecondBatch.await()
                    }
                    respondJson(WyrJson.encodeToString(if (request == 0) batchOf("q1", "q2") else batchOf("q2", "q3")))
                }
            val repository = repositoryOver(engine)
            assertEquals("q1", repository.next().id)

            val refill = async { repository.prefetch() }
            secondBatchRequested.await()
            // Taken from the queue without waiting for the refill, which then brings it back.
            assertEquals("q2", repository.next().id)
            answerSecondBatch.complete(Unit)
            refill.await()

            assertEquals(listOf("q3"), List(cache.count()) { repository.next().id })
        }

    @Test
    fun `a question handed out earlier comes back when the feed loops to it`() =
        runTest {
            // The player answered both, so the feed loops. q2 is the one on screen.
            val looped = batchOf("q2", "q1", answeredBefore = true)
            val repository = repositoryOver(feedOf(batchOf("q1", "q2"), looped))
            repository.next()
            repository.next()

            val question = repository.next()

            assertEquals("q1", question.id)
            assertTrue(question.answeredBefore)
        }

    @Test
    fun `a batch of only the question on screen shows it again instead of ending the game`() =
        runTest {
            // A pool of one question: the feed can only loop back to the one just answered.
            val engine = feedOf(batchOf("q1"))
            val repository = repositoryOver(engine)
            assertEquals("q1", repository.next().id)

            assertEquals("q1", repository.next().id)
            assertEquals(2, engine.requestHistory.size)
        }

    @Test
    fun `an empty batch is OUT_OF_QUESTIONS`() =
        runTest {
            val repository = repositoryOver(feedOf(batchOf()))

            val failure = assertFailsWith<WyrException> { repository.next() }

            assertEquals(DomainError.OUT_OF_QUESTIONS, failure.error)
        }

    @Test
    fun `a reset drops the queue so the next question comes from a fresh batch`() =
        runTest {
            val engine = feedOf(batchOf("q1", "q2", "q3"), batchOf("q4", "q5"))
            val repository = repositoryOver(engine)
            assertEquals("q1", repository.next().id)

            repository.reset()

            assertEquals(0, cache.count())
            // Without the reset this is q2, still queued from the first batch.
            assertEquals("q4", repository.next().id)
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
                    respondJson(WyrJson.encodeToString(batchOf("q1", "q2", "q3")))
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

    @Test
    fun `a fetch on a session the server no longer knows is retried as a fresh guest`() =
        runTest {
            // The server has never heard of "a": the state after a dev server restarts.
            val server = FakeServer()
            val store = storeHolding(session("a"))
            val repository = repositoryOver(server.engine, store)

            assertEquals("q1", repository.next().id)

            assertEquals(1, server.guestsMinted)
            assertEquals("guest1", store.read()?.playerId)
            assertEquals(listOf<String?>("Bearer access-a", "Bearer access-guest1"), server.feedsSentAs)
        }

    @Test
    fun `a first launch's first question is asked for with a bearer already`() =
        runTest {
            val server = FakeServer()
            val store = storeHolding(null)
            val client = WyrHttpClient.create(BASE_URL, store, server.engine)
            val sessions = DefaultSessionRepository(AuthApi(client), store)
            val getNextQuestion =
                GetNextQuestion(DefaultQuestionRepository(QuestionApi(client), sessions, cache), sessions)

            assertEquals("q1", getNextQuestion().id)

            // Not refused and then recovered: the session was ensured before the feed was asked.
            assertEquals(listOf<String?>("Bearer access-guest1"), server.feedsSentAs)
        }

    private fun repositoryOver(
        engine: HttpClientEngine,
        store: SessionStore = storeHolding(session("a")),
    ): DefaultQuestionRepository {
        val client = WyrHttpClient.create(BASE_URL, store, engine)
        return DefaultQuestionRepository(QuestionApi(client), DefaultSessionRepository(AuthApi(client), store), cache)
    }

    /** A feed that answers the n-th request with the n-th batch, and every later one with the last. */
    private fun feedOf(vararg batches: QuestionPageDto): MockEngine {
        var served = 0
        return MockEngine {
            respondJson(WyrJson.encodeToString(batches[minOf(served++, batches.lastIndex)]))
        }
    }

    private fun batchOf(
        vararg ids: String,
        answeredBefore: Boolean = false,
    ): QuestionPageDto =
        QuestionPageDto(
            questions =
                ids.map { id ->
                    QuestionDto(
                        id = id,
                        optionA = "$id-a",
                        optionB = "$id-b",
                        category = QuestionCategory.FOOD,
                        answeredBefore = answeredBefore,
                    )
                },
        )
}

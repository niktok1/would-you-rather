package io.ntole.wyr.core.data.question

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.cache.InMemoryQuestionCache
import io.ntole.wyr.core.data.respondJson
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.SkipQuestion
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.QuestionApi
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
            // The server read the batch while q1 was on screen and q2 queued, so it lists both.
            val feed = HeldRefill(batchOf("q1", "q2"), batchOf("q1", "q2", "q3"))
            val repository = repositoryOver(feed.engine)
            assertEquals("q1", repository.next().id)

            val refill = async { repository.prefetch() }
            feed.requested.await()
            // The player answers q1 and moves on without waiting for the refill.
            assertEquals("q2", repository.next().id)
            feed.release.complete(Unit)
            refill.await()

            // Not q1 again: answered by now, and it would be served next as though it were not.
            assertEquals(listOf("q3"), List(cache.count()) { repository.next().id })
        }

    @Test
    fun `every question handed out while a refill is in flight is kept out of it`() =
        runTest {
            val feed = HeldRefill(batchOf("q1", "q2", "q3"), batchOf("q1", "q2", "q3", "q4"))
            val repository = repositoryOver(feed.engine)
            assertEquals("q1", repository.next().id)

            val refill = async { repository.prefetch() }
            feed.requested.await()
            assertEquals("q2", repository.next().id)
            assertEquals("q3", repository.next().id)
            feed.release.complete(Unit)
            refill.await()

            // Not only the one on screen when the batch lands: q2 is kept out too.
            assertEquals(listOf("q4"), List(cache.count()) { repository.next().id })
        }

    @Test
    fun `a question kept out of a refill is queued by the next batch that serves it`() =
        runTest {
            // The player moves past q1 and q2 without answering or skipping them, so the third batch
            // still serves them as due.
            val feed =
                HeldRefill(batchOf("q1", "q2"), batchOf("q1", "q2", "q3"), batchOf("q1", "q2", "q3"))
            val repository = repositoryOver(feed.engine)
            assertEquals("q1", repository.next().id)
            val refill = async { repository.prefetch() }
            feed.requested.await()
            assertEquals("q2", repository.next().id)
            feed.release.complete(Unit)
            refill.await()
            assertEquals("q3", repository.next().id)

            repository.prefetch()

            assertEquals(listOf("q1", "q2"), List(cache.count()) { repository.next().id })
        }

    @Test
    fun `a question handed out earlier comes back when the feed loops to it`() =
        runTest {
            // The player answered both, so the feed loops. q2 is the one on screen.
            val looped = batchOf("q2", "q1", answeredBefore = true)
            val repository = repositoryOver(feedOf(batchOf("q1", "q2"), looped))
            repository.next()
            repository.next()

            assertEquals("q1", repository.next().id)
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
    fun `a feed nobody filtered asks for every category`() =
        runTest {
            val feed = CategoryFeed()
            val repository = repositoryOver(feed.engine)

            repository.next()

            assertEquals(emptySet(), repository.categories.value)
            assertEquals(listOf(emptyList<String>()), feed.asked)
        }

    @Test
    fun `a category switch drops what was queued for the selection before`() =
        runTest {
            val feed = CategoryFeed()
            val repository = repositoryOver(feed.engine)
            assertEquals("q1", repository.next().id)

            repository.setCategories(setOf(Category.ETHICS))

            assertEquals(setOf(Category.ETHICS), repository.categories.value)
            assertEquals(0, cache.count())
            // Without the switch dropping the queue, this is q2, still queued from the unfiltered batch.
            val served = List(3) { repository.next() }
            assertEquals(listOf("e1", "e2", "e3"), served.map { it.id })
            assertEquals(setOf(setOf(Category.ETHICS)), served.map { it.categories }.toSet())
            assertEquals(listOf(emptyList(), listOf("ETHICS")), feed.asked)
        }

    @Test
    fun `taking a category out of the selection drops its questions from the queue`() =
        runTest {
            val feed = CategoryFeed()
            val repository = repositoryOver(feed.engine)
            repository.setCategories(setOf(Category.FOOD, Category.ETHICS))
            assertEquals("f1", repository.next().id)

            repository.setCategories(setOf(Category.ETHICS))

            assertEquals(0, cache.count())
            // Without the change dropping the queue, this is f2, a FOOD question no longer selected.
            assertEquals("e1", repository.next().id)
            assertEquals(listOf(listOf("FOOD", "ETHICS"), listOf("ETHICS")), feed.asked)
        }

    @Test
    fun `adding a category to the selection asks the feed for it at once`() =
        runTest {
            val feed = CategoryFeed()
            val repository = repositoryOver(feed.engine)
            repository.setCategories(setOf(Category.FOOD))
            assertEquals("f1", repository.next().id)

            repository.setCategories(setOf(Category.FOOD, Category.ETHICS))

            // f2 still comes first, but from a batch that asked for ETHICS too: the queue the FOOD batch
            // left would have held ETHICS off until it ran out.
            assertEquals(0, cache.count())
            assertEquals("f2", repository.next().id)
            assertEquals(listOf(listOf("FOOD"), listOf("FOOD", "ETHICS")), feed.asked)
        }

    @Test
    fun `a refill in flight when the category switches cannot put the old selection's questions back`() =
        runTest {
            val unfilteredRequested = CompletableDeferred<Unit>()
            val answerUnfiltered = CompletableDeferred<Unit>()
            val feed =
                CategoryFeed(
                    beforeAnswering = { categories ->
                        if (categories.isEmpty()) {
                            unfilteredRequested.complete(Unit)
                            answerUnfiltered.await()
                        }
                    },
                )
            val repository = repositoryOver(feed.engine)

            val refill = async { repository.prefetch() }
            unfilteredRequested.await()
            // Runs until it has to wait: the refill holds the lock while its batch is in flight.
            val switch =
                launch(start = CoroutineStart.UNDISPATCHED) { repository.setCategories(setOf(Category.ETHICS)) }
            answerUnfiltered.complete(Unit)
            refill.await()
            switch.join()

            assertEquals(0, cache.count())
            assertEquals("e1", repository.next().id)
        }

    @Test
    fun `every refill after a switch asks for every category selected`() =
        runTest {
            val feed = CategoryFeed()
            val repository = repositoryOver(feed.engine)
            repository.setCategories(setOf(Category.RANDOM, Category.FOOD))

            repository.next()
            // A prefetch, and the next that finds the queue empty after it.
            repository.prefetch()
            repeat(cache.count()) { repository.next() }
            repository.next()

            assertEquals(List(3) { listOf("FOOD", "ABSURD") }, feed.asked)
        }

    @Test
    fun `selecting no category lifts the filter`() =
        runTest {
            val feed = CategoryFeed()
            val repository = repositoryOver(feed.engine)
            repository.setCategories(setOf(Category.FOOD))
            assertEquals("f1", repository.next().id)

            repository.setCategories(emptySet())

            assertEquals(emptySet(), repository.categories.value)
            assertEquals("q1", repository.next().id)
            assertEquals(listOf(listOf("FOOD"), emptyList()), feed.asked)
        }

    @Test
    fun `selecting the categories already selected keeps the queue`() =
        runTest {
            val feed = CategoryFeed()
            val repository = repositoryOver(feed.engine)
            repository.setCategories(setOf(Category.FOOD, Category.ETHICS))
            assertEquals("f1", repository.next().id)

            // The same set, whatever order it was put together in.
            repository.setCategories(setOf(Category.ETHICS, Category.FOOD))

            assertEquals("f2", repository.next().id)
            assertEquals(listOf(listOf("FOOD", "ETHICS")), feed.asked, "not a fetch")
        }

    @Test
    fun `a set changed after it was selected changes nothing`() =
        runTest {
            val feed = CategoryFeed()
            val repository = repositoryOver(feed.engine)
            val selection = mutableSetOf(Category.FOOD)
            repository.setCategories(selection)

            selection += Category.ETHICS
            repository.next()

            assertEquals(setOf(Category.FOOD), repository.categories.value)
            assertEquals(listOf(listOf("FOOD")), feed.asked)
        }

    @Test
    fun `a reset keeps the categories selected`() =
        runTest {
            // The queue was the old player's, but the selection is what to ask the feed for.
            val feed = CategoryFeed()
            val repository = repositoryOver(feed.engine)
            repository.setCategories(setOf(Category.FOOD))
            repository.next()

            repository.reset()

            assertEquals(setOf(Category.FOOD), repository.categories.value)
            // Not f1, which is still on screen.
            assertEquals("f2", repository.next().id)
            assertEquals(listOf(listOf("FOOD"), listOf("FOOD")), feed.asked)
        }

    @Test
    fun `OTHER cannot be selected and changes nothing`() =
        runTest {
            val feed = CategoryFeed()
            val repository = repositoryOver(feed.engine)
            repository.setCategories(setOf(Category.FOOD))
            assertEquals("f1", repository.next().id)

            assertFailsWith<IllegalArgumentException> {
                repository.setCategories(setOf(Category.ETHICS, Category.OTHER))
            }

            assertEquals(setOf(Category.FOOD), repository.categories.value)
            assertEquals("f2", repository.next().id)
            assertEquals(listOf(listOf("FOOD")), feed.asked)
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

    @Test
    fun `a first launch's skip is sent once with the session just minted`() =
        runTest {
            val server = FakeServer()
            val store = storeHolding(null)
            val client = WyrHttpClient.create(BASE_URL, store, server.engine)
            val sessions = DefaultSessionRepository(AuthApi(client), store)
            val skipQuestion = SkipQuestion(DefaultQuestionRepository(QuestionApi(client), sessions, cache), sessions)

            skipQuestion("q1")

            // Not refused and then recovered: the session was ensured before the skip went out.
            assertEquals(listOf<Pair<String?, String>>("Bearer access-guest1" to "q1"), server.skipsSentAs)
        }

    @Test
    fun `a skip on a session the server no longer knows is sent again as a fresh guest`() =
        runTest {
            // The server has never heard of "a": the state after a dev server restarts.
            val server = FakeServer()
            val store = storeHolding(session("a"))

            repositoryOver(server.engine, store).skip("q1")

            assertEquals(1, server.guestsMinted)
            assertEquals("guest1", store.read()?.playerId)
            assertEquals(
                listOf<Pair<String?, String>>("Bearer access-a" to "q1", "Bearer access-guest1" to "q1"),
                server.skipsSentAs,
            )
        }

    @Test
    fun `a refused skip is a domain error and leaves the session alone`() =
        runTest {
            val server = FakeServer()
            server.refuseSkipsWith = HttpStatusCode.NotFound to ErrorCode.QUESTION_NOT_FOUND
            val store = storeHolding(session("a"))

            val failure = assertFailsWith<WyrException> { repositoryOver(server.engine, store).skip("gone") }

            assertEquals(DomainError.QUESTION_NOT_FOUND, failure.error)
            assertEquals(0, server.guestsMinted)
            assertEquals(session("a"), store.read())
        }

    @Test
    fun `a skip leaves the queue as it is`() =
        runTest {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath == WyrApi.Paths.SKIPS) {
                        respond("", HttpStatusCode.NoContent)
                    } else {
                        respondJson(WyrJson.encodeToString(batchOf("q1", "q2", "q3")))
                    }
                }
            val repository = repositoryOver(engine)
            assertEquals("q1", repository.next().id)

            repository.skip("q1")

            assertEquals(listOf("q2", "q3"), List(cache.count()) { repository.next().id })
            assertEquals(1, engine.requestHistory.count { it.url.encodedPath == WyrApi.Paths.QUESTIONS }, "not a fetch")
        }

    private fun repositoryOver(
        engine: HttpClientEngine,
        store: SessionStore = storeHolding(session("a")),
    ): DefaultQuestionRepository {
        val client = WyrHttpClient.create(BASE_URL, store, engine)
        return DefaultQuestionRepository(QuestionApi(client), DefaultSessionRepository(AuthApi(client), store), cache)
    }

    /** A [feedOf] whose second request stays in flight, once [requested], until [release]. */
    private class HeldRefill(
        vararg batches: QuestionPageDto,
    ) {
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private var served = 0
        val engine =
            MockEngine {
                val request = served++
                if (request == 1) {
                    requested.complete(Unit)
                    release.await()
                }
                respondJson(WyrJson.encodeToString(batches[minOf(request, batches.lastIndex)]))
            }
    }

    /**
     * A feed that answers each request with three questions of every category it asks for, in the
     * order asked, their ids the category's initial and 1 to 3, and an unfiltered one with q1 to q3.
     * [asked] holds the categories every request asked for, in order, none for every category.
     * [beforeAnswering] runs first, so a test can hold a request in flight.
     */
    private inner class CategoryFeed(
        private val beforeAnswering: suspend (categories: List<String>) -> Unit = {},
    ) {
        val asked = mutableListOf<List<String>>()
        val engine =
            MockEngine { request ->
                val categories =
                    request.url.parameters
                        .getAll(WyrApi.Query.CATEGORY)
                        .orEmpty()
                asked += categories
                beforeAnswering(categories)
                val batch =
                    if (categories.isEmpty()) {
                        batchOf("q1", "q2", "q3")
                    } else {
                        val questions =
                            categories.flatMap { category ->
                                val ids = (1..3).map { "${category.first().lowercase()}$it" }
                                val batch = batchOf(*ids.toTypedArray(), category = category)
                                batch.questions
                            }
                        QuestionPageDto(questions = questions)
                    }
                respondJson(WyrJson.encodeToString(batch))
            }
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
        category: String = "FOOD",
    ): QuestionPageDto =
        QuestionPageDto(
            questions =
                ids.map { id ->
                    QuestionDto(
                        id = id,
                        optionA = "$id-a",
                        optionB = "$id-b",
                        categories = listOf(category),
                        answeredBefore = answeredBefore,
                    )
                },
        )
}

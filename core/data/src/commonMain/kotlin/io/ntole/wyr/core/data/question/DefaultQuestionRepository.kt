package io.ntole.wyr.core.data.question

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.withSessionRecovery
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionCache
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.network.api.QuestionApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serves questions from the cache, refilling from the server's per-player feed when it runs low.
 *
 * The feed needs a session, so a refill goes through [withSessionRecovery] as a vote does: with no
 * session stored, or a dead one, the refused fetch mints a guest and is retried once as it.
 *
 * The server knows what the player has answered, so nothing here remembers what was served. A
 * batch leads with every question still unanswered, and that includes the ones still queued and
 * the one on screen. The cache drops the queued ones. The one [next] handed out last is dropped
 * here, or a refill while the player looks at it would queue it again, and show it twice in a row
 * when nothing else is queued.
 */
public class DefaultQuestionRepository(
    private val api: QuestionApi,
    private val session: DefaultSessionRepository,
    private val cache: QuestionCache,
    private val lowWaterMark: Int = LOW_WATER_MARK,
) : QuestionRepository {
    /** One fetch at a time. Held across the request. */
    private val refillMutex = Mutex()

    /**
     * Makes taking a question and recording it in [lastHandedOut] one step, as seen by a refill
     * filtering its batch. Never held across a request, so [next] does not wait on a prefetch.
     */
    private val handOutMutex = Mutex()

    /** The id of the question [next] returned last, which is the one on screen. */
    private var lastHandedOut: String? = null

    override suspend fun next(): Question {
        takeNext()?.let { return it }

        return refillMutex.withLock {
            // Another caller may have refilled while we waited for the lock.
            takeNext()?.let { return@withLock it }

            val batch = fetchAndQueue()
            // Nothing queued but a batch that was not empty: it held only the question on screen,
            // as a pool of one question does. The feed is endless, so that one is shown again.
            takeNext() ?: batch.firstOrNull()?.also { handOutMutex.withLock { lastHandedOut = it.id } }
        } ?: throw WyrException(DomainError.OUT_OF_QUESTIONS, "server returned no questions")
    }

    override suspend fun prefetch() {
        if (cache.count() > lowWaterMark) return
        refillMutex.withLock {
            // Another caller may have refilled while we waited for the lock.
            if (cache.count() > lowWaterMark) return
            fetchAndQueue()
        }
    }

    // Under the refill lock, so a refill that fetched before the reset has put its batch before the
    // cache is cleared, rather than after it. The last question handed out is still on screen, so it
    // stays excluded from the next batch.
    override suspend fun reset() {
        refillMutex.withLock { cache.clear() }
    }

    private suspend fun takeNext(): Question? =
        handOutMutex.withLock {
            cache.takeNext()?.also { lastHandedOut = it.id }
        }

    /** Fetches the next batch and queues what it can. Returns the whole batch. Needs [refillMutex]. */
    private suspend fun fetchAndQueue(): List<Question> {
        val batch =
            session
                .withSessionRecovery { api.page(limit = WyrApi.Limits.DEFAULT_PAGE_SIZE) }
                .questions
                .map { it.toDomain() }

        handOutMutex.withLock { cache.put(batch.filter { it.id != lastHandedOut }) }
        return batch
    }

    private companion object {
        /** Refill once the queue drops to this many questions. */
        const val LOW_WATER_MARK = 5
    }
}

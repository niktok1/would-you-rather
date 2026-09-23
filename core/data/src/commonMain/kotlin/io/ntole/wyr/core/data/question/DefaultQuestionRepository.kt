package io.ntole.wyr.core.data.question

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionCache
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.network.api.QuestionApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serves questions from the cache, refilling from the server when it runs low.
 */
public class DefaultQuestionRepository(
    private val api: QuestionApi,
    private val cache: QuestionCache,
    private val lowWaterMark: Int = LOW_WATER_MARK,
) : QuestionRepository {
    private val refillMutex = Mutex()
    private var nextCursor: String? = null
    private var reachedEnd = false

    override suspend fun next(): Question {
        cache.takeNext()?.let { return it }

        refill()

        return cache.takeNext()
            ?: throw WyrException(
                DomainError.OUT_OF_QUESTIONS,
                "server returned no questions",
            )
    }

    override suspend fun prefetch() {
        if (cache.count() > lowWaterMark) return
        refill()
    }

    // Under the refill lock, so a refill that fetched before the reset has put its page before the
    // cache is cleared, rather than after it.
    override suspend fun reset() {
        refillMutex.withLock {
            cache.clear()
            nextCursor = null
            reachedEnd = false
        }
    }

    private suspend fun refill() {
        refillMutex.withLock {
            // Another caller may have refilled while we waited for the lock.
            if (cache.count() > lowWaterMark) return

            // Walked off the end of the catalogue: start over. The pool is finite and the game
            // is endless, so replaying is the intended behaviour, not an error.
            if (reachedEnd) {
                nextCursor = null
                reachedEnd = false
            }

            val page =
                runApi {
                    api.page(cursor = nextCursor, limit = WyrApi.Limits.DEFAULT_PAGE_SIZE)
                }

            cache.put(page.questions.map { it.toDomain() })
            nextCursor = page.nextCursor
            reachedEnd = page.nextCursor == null
        }
    }

    private companion object {
        /** Refill once the queue drops to this many questions. */
        const val LOW_WATER_MARK = 5
    }
}

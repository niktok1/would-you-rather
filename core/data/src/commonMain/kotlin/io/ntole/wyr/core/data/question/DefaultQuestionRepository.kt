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
 */
public class DefaultQuestionRepository(
    private val api: QuestionApi,
    private val session: DefaultSessionRepository,
    private val cache: QuestionCache,
    private val lowWaterMark: Int = LOW_WATER_MARK,
) : QuestionRepository {
    private val refillMutex = Mutex()

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

    // Under the refill lock, so a refill that fetched before the reset has put its batch before the
    // cache is cleared, rather than after it.
    override suspend fun reset() {
        refillMutex.withLock { cache.clear() }
    }

    private suspend fun refill() {
        refillMutex.withLock {
            // Another caller may have refilled while we waited for the lock.
            if (cache.count() > lowWaterMark) return

            // No cursor to keep: the server knows what this player has answered, so every refill
            // just asks for the next batch.
            val batch =
                session.withSessionRecovery {
                    api.page(limit = WyrApi.Limits.DEFAULT_PAGE_SIZE)
                }

            cache.put(batch.questions.map { it.toDomain() })
        }
    }

    private companion object {
        /** Refill once the queue drops to this many questions. */
        const val LOW_WATER_MARK = 5
    }
}

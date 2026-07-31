package io.ntole.wyr.core.data.cache

import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-lifetime queue of unplayed questions.
 *
 * This is the current [QuestionCache] on every platform. CLAUDE.md §4 designates SQLDelight for
 * the local cache, and it remains the intended implementation for Android, iOS, and desktop —
 * but SQLDelight has no driver for wasmJs, and web is now an in-scope target, so the cache has
 * to become a per-platform split rather than one shared implementation. That split is its own
 * change; until then this serves all platforms and the queue simply does not survive restart.
 */
public class InMemoryQuestionCache : QuestionCache {
    private val mutex = Mutex()
    private val queue = ArrayDeque<Question>()
    private val seenIds = mutableSetOf<String>()

    override suspend fun put(questions: List<Question>): Unit =
        mutex.withLock {
            // Pages can overlap when the server reshuffles between requests; dropping ids we
            // have already queued stops the same question appearing twice in one session.
            questions.forEach { question ->
                if (seenIds.add(question.id)) queue.addLast(question)
            }
        }

    override suspend fun takeNext(): Question? = mutex.withLock { queue.removeFirstOrNull() }

    override suspend fun count(): Int = mutex.withLock { queue.size }

    override suspend fun clear(): Unit =
        mutex.withLock {
            queue.clear()
            seenIds.clear()
        }
}

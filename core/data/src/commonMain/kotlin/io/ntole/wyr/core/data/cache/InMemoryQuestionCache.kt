package io.ntole.wyr.core.data.cache

import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-lifetime queue of questions still to be handed out.
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

    override suspend fun put(questions: List<Question>): Unit =
        mutex.withLock {
            // The feed serves a question until it is answered, so a batch repeats whatever is still
            // queued from the last one. Only the queue is checked: a question handed out before must
            // be queued again when the feed loops back to it (CLAUDE.md §8d). Remembering every id
            // ever queued is what used to end the game after one pass.
            val queued = queue.mapTo(mutableSetOf()) { it.id }
            questions.forEach { question ->
                if (queued.add(question.id)) queue.addLast(question)
            }
        }

    override suspend fun takeNext(): Question? = mutex.withLock { queue.removeFirstOrNull() }

    override suspend fun count(): Int = mutex.withLock { queue.size }

    override suspend fun clear(): Unit = mutex.withLock { queue.clear() }
}

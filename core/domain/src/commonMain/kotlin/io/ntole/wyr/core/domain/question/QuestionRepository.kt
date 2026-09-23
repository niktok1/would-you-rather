package io.ntole.wyr.core.domain.question

/**
 * Source of questions to play, wherever they actually come from.
 *
 * Implemented in `:core:data`. Domain and UI depend only on this.
 */
public interface QuestionRepository {
    /**
     * The next question to play, fetching more if the local supply is low.
     *
     * The feed is endless (CLAUDE.md §8d): once the player has answered everything, answered
     * questions come back. So there is always a next question while the server has any at all.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException with
     *   [io.ntole.wyr.core.domain.error.DomainError.OUT_OF_QUESTIONS] when the cache is empty and
     *   the server sent an empty batch.
     */
    public suspend fun next(): Question

    /** Top the local supply back up ahead of time. Safe to call redundantly. */
    public suspend fun prefetch()

    /**
     * Drop every queued question, so the next [next] fetches a fresh batch.
     *
     * For when the player changes: the queue was filled from the old one's feed. A refill already
     * in flight lands before the reset does, so it cannot put old questions back after it.
     */
    public suspend fun reset()
}

/**
 * Local queue of questions still to be handed out.
 *
 * A port, not an implementation: this is what lets the cache be swapped (in-memory today,
 * SQLDelight on the platforms that can host it) without any change above `:core:data`.
 */
public interface QuestionCache {
    /**
     * Queue [questions] in order, skipping any whose id is already queued.
     *
     * Only the queue counts. A question taken earlier is queued again, because the feed loops.
     */
    public suspend fun put(questions: List<Question>)

    /** Remove and return the next question, or `null` when empty. */
    public suspend fun takeNext(): Question?

    public suspend fun count(): Int

    public suspend fun clear()
}

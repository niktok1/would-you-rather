package io.ntole.wyr.core.domain.question

/**
 * Source of questions to play, wherever they actually come from.
 *
 * Implemented in `:core:data`. Domain and UI depend only on this.
 */
public interface QuestionRepository {
    /**
     * The next unplayed question, fetching more if the local supply is low.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException with
     *   [io.ntole.wyr.core.domain.error.DomainError.OUT_OF_QUESTIONS] when neither the cache nor
     *   the server can supply one.
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
 * Local store of not-yet-played questions.
 *
 * A port, not an implementation: this is what lets the cache be swapped (in-memory today,
 * SQLDelight on the platforms that can host it) without any change above `:core:data`.
 */
public interface QuestionCache {
    public suspend fun put(questions: List<Question>)

    /** Remove and return the next question, or `null` when empty. */
    public suspend fun takeNext(): Question?

    public suspend fun count(): Int

    public suspend fun clear()
}

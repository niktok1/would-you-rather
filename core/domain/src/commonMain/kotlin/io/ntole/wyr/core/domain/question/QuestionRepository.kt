package io.ntole.wyr.core.domain.question

import kotlinx.coroutines.flow.StateFlow

/**
 * Source of questions to play, wherever they actually come from.
 *
 * Implemented in `:core:data`. Domain and UI depend only on this.
 */
public interface QuestionRepository {
    /**
     * The category [next] and [prefetch] ask the feed for, or `null` for every category. It starts
     * as `null`, and only [setCategory] changes it.
     */
    public val category: StateFlow<Category?>

    /**
     * The next question to play, fetching more if the local supply is low.
     *
     * The feed is endless (CLAUDE.md §8d): once the player has answered everything, answered
     * questions come back. So there is always a next question while the server has any at all, in
     * the selected [category] if there is one.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException with
     *   [io.ntole.wyr.core.domain.error.DomainError.OUT_OF_QUESTIONS] when the cache is empty and
     *   the server sent an empty batch.
     */
    public suspend fun next(): Question

    /** Top the local supply back up ahead of time. Safe to call redundantly. */
    public suspend fun prefetch()

    /**
     * Filters the feed to [category], or back to every category with `null`.
     *
     * A change drops every queued question, so nothing queued for the selection before is handed
     * out after this returns. A refill already in flight lands before the change does, as it does
     * before a [reset], so it cannot put them back after it. Selecting the category already
     * selected changes nothing. A category is played within the player's cycle, not a cycle of its
     * own: once nothing in it is due, it is served again (CLAUDE.md §8d, *Categories*).
     *
     * @throws IllegalArgumentException for [Category.OTHER], which is not in
     *   [Category.selectable], leaving the selection and the queue as they were.
     */
    public suspend fun setCategory(category: Category?)

    /**
     * Skips the question [questionId] for the rest of the player's current cycle (CLAUDE.md §8d).
     * It pays nothing, and the feed serves it again in the next cycle; only a feed filtered to a
     * [category] with nothing due in it can serve it sooner (provisional, CLAUDE.md §8b). Skipping
     * it again in the same cycle changes nothing. It is not a fetch: [next] still hands out
     * whatever comes next.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, with
     *   [io.ntole.wyr.core.domain.error.DomainError.QUESTION_NOT_FOUND] for a question the server
     *   does not have.
     */
    public suspend fun skip(questionId: String)

    /**
     * Drop every queued question, so the next [next] fetches a fresh batch.
     *
     * For when the player changes: the queue was filled from the old one's feed. A refill already
     * in flight lands before the reset does, so it cannot put old questions back after it. The
     * [category] stays selected: it is what to ask the feed for, not anything of the old player's.
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

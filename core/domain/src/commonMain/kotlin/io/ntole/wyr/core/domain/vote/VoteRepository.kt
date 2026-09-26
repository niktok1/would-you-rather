package io.ntole.wyr.core.domain.vote

/** Casts votes and reports back what the server decided. Implemented in `:core:data`. */
public interface VoteRepository {
    /**
     * Sends one answer as [attempt]. A retry of that same answer passes the same [attempt] again
     * (see [AttemptId]), and the same [answerMillis]: how long the player took to answer, from the
     * question showing to the tap, or null when nothing measured it (CLAUDE.md §8b,
     * *Personalization*).
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure.
     */
    public suspend fun cast(
        questionId: String,
        side: Side,
        attempt: AttemptId,
        answerMillis: Long? = null,
    ): VoteOutcome
}

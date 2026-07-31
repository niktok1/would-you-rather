package io.ntole.wyr.core.domain.vote

/** Casts votes and reports back what the server decided. Implemented in `:core:data`. */
public interface VoteRepository {
    /**
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, including
     *   [io.ntole.wyr.core.domain.error.DomainError.ALREADY_VOTED].
     */
    public suspend fun cast(
        questionId: String,
        side: Side,
    ): VoteOutcome
}

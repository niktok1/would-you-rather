package io.ntole.wyr.core.domain.submission

/** The session player's own questions, as the server holds them. Implemented in `:core:data`. */
public interface SubmissionRepository {
    /**
     * Submits a question of the player's own, filed under [categories], by id, and returns it as the
     * server stored it: pending, with both options trimmed and its categories each once (CLAUDE.md
     * §8d).
     *
     * [categories] may be empty, the author saying none fits, and [categorySuggestion] is a category
     * they suggest then, or null for none (CLAUDE.md §8d, *Categories*, *Nothing fits*): the moderator
     * files the question when approving it. An id no category has is refused as a malformed request,
     * which a picker of the server's categories never sends. The options and the suggestion are sent
     * as given. What they may say is the server's to rule on, so nothing here checks them.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any other failure, with
     *   [io.ntole.wyr.core.domain.error.DomainError.INVALID_SUBMISSION] for options the server's
     *   rules refuse, [io.ntole.wyr.core.domain.error.DomainError.SUBMISSION_LIMIT] when the
     *   player already has as many submissions pending as they may, and
     *   [io.ntole.wyr.core.domain.error.DomainError.NOT_ENOUGH_POINTS] when they have fewer points
     *   than submitting costs (CLAUDE.md §8c), which a stored question has taken from them.
     */
    public suspend fun submit(
        optionA: String,
        optionB: String,
        categories: Set<String>,
        categorySuggestion: String? = null,
    ): Submission

    /**
     * Every question the session player has submitted, whatever its status, newest first, read from
     * the server every time.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure.
     */
    public suspend fun mine(): List<Submission>
}

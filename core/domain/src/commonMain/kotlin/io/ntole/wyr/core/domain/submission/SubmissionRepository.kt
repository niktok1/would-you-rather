package io.ntole.wyr.core.domain.submission

import io.ntole.wyr.core.domain.question.Category

/** The session player's own questions, as the server holds them. Implemented in `:core:data`. */
public interface SubmissionRepository {
    /**
     * Submits a question of the player's own, filed under [categories], and returns it as the server
     * stored it: pending, with both options trimmed and its categories each once (CLAUDE.md §8d).
     *
     * [categories] must hold at least one, and only categories in [Category.selectable]: the server
     * refuses a submission under none as a malformed request, and [Category.OTHER] names nothing it
     * can file a question under. The options are sent as given. What an option may say is the
     * server's to rule on, so nothing here checks them.
     *
     * @throws IllegalArgumentException when [categories] is empty or holds [Category.OTHER], having
     *   sent nothing.
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
        categories: Set<Category>,
    ): Submission

    /**
     * Every question the session player has submitted, whatever its status, newest first, read from
     * the server every time.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure.
     */
    public suspend fun mine(): List<Submission>
}

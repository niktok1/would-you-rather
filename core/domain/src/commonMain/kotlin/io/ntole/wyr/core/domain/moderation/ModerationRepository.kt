package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus

/**
 * The players' submissions as the moderator decides them, and every question as the moderator
 * retires and restores it (CLAUDE.md §8d, *Moderation*). Implemented in `:core:data`.
 *
 * Every call sends the [AdminToken] it is given, and nothing here keeps it. None needs a player
 * session or touches the one there is: the moderator is whoever holds the token, not a player. A
 * submission comes back as its author sees it, which is how a moderator sees it too, with no author.
 *
 * Every call throws [io.ntole.wyr.core.domain.error.WyrException] on any failure, with
 * [io.ntole.wyr.core.domain.error.DomainError.FORBIDDEN] for a token that is not the server's. A
 * server with moderation off has no admin routes, and each call then fails as a path the server
 * does not have, which reads as [io.ntole.wyr.core.domain.error.DomainError.UNKNOWN].
 */
public interface ModerationRepository {
    /**
     * The submissions waiting for a decision, oldest first, at most [PAGE_SIZE] of them, read from the
     * server every time. So the first is the next to decide, and asking again once it is decided gets
     * the rest. A list of [PAGE_SIZE] may not be all of them.
     */
    public suspend fun pending(token: AdminToken): List<Submission>

    /**
     * Approves the pending submission [questionId] and returns it as its author now sees it. It is
     * filed under [categories], by id, in place of the ones its author picked, or under the author's
     * when [categories] is empty, and served from then on to every player, its author included.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, with
     *   [io.ntole.wyr.core.domain.error.DomainError.ALREADY_DECIDED] for a submission that is not
     *   pending (decided already, by this moderator or another, or a seed) and
     *   [io.ntole.wyr.core.domain.error.DomainError.QUESTION_NOT_FOUND] for an id no question has.
     */
    public suspend fun approve(
        token: AdminToken,
        questionId: String,
        categories: Set<String>,
    ): Submission

    /**
     * Rejects the pending submission [questionId] with [reason], which its author sees, and returns it
     * as its author now sees it. It is served to nobody, ever.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, as [approve] does.
     */
    public suspend fun reject(
        token: AdminToken,
        questionId: String,
        reason: RejectionReason,
    ): Submission

    /**
     * One page of every question, seeds included, newest first, that [filter] picks: the first page
     * for no [after], or the one after the page whose [ModeratedQuestionPage.next] [after] is, which
     * must be asked for with the same [filter]. At most [PAGE_SIZE] questions, read from the server
     * every time.
     *
     * @throws IllegalArgumentException when [filter] holds [SubmissionStatus.OTHER], which names no
     *   status the server can filter by, having sent nothing.
     * @throws io.ntole.wyr.core.domain.error.WyrException on any other failure, as [pending] does.
     */
    public suspend fun questions(
        token: AdminToken,
        filter: QuestionFilter,
        after: QuestionCursor?,
    ): ModeratedQuestionPage

    /**
     * Retires the approved question [questionId], a seed included, and returns it as the list now
     * shows it, [SubmissionStatus.RETIRED]. From then on it is served to nobody until [restore]
     * restores it, and nothing it earned is taken back.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, with
     *   [io.ntole.wyr.core.domain.error.DomainError.WRONG_STATUS] for a question that is not approved,
     *   a retired one included, and [io.ntole.wyr.core.domain.error.DomainError.QUESTION_NOT_FOUND] for
     *   an id no question has.
     */
    public suspend fun retire(
        token: AdminToken,
        questionId: String,
    ): ModeratedQuestion

    /**
     * Restores the retired question [questionId] and returns it as the list now shows it, approved
     * again: due for every player who has neither answered nor skipped it in their current cycle.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, as [retire] does, with
     *   [io.ntole.wyr.core.domain.error.DomainError.WRONG_STATUS] for a question that is not retired.
     */
    public suspend fun restore(
        token: AdminToken,
        questionId: String,
    ): ModeratedQuestion

    public companion object {
        /**
         * The most submissions [pending] lists, and questions a page of [questions] holds: the most
         * the server lists at once, so the moderator's reads are few, each one a request of the
         * address's admin budget (CLAUDE.md §8b). It is the server's `WyrApi.Limits.MAX_PAGE_SIZE`,
         * which this module cannot see (CLAUDE.md §3), so `:core:data`'s tests pin the two equal.
         */
        public const val PAGE_SIZE: Int = 100
    }
}

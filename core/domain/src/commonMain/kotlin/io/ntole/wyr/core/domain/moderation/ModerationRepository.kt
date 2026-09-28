package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus

/**
 * The players' submissions as the moderator decides them, every question as the moderator retires
 * and restores it, the categories as the moderator adds and renames them, the questions players
 * reported, their authors as the moderator blocks and unblocks them, and the players' accounts as
 * the moderator deletes them on request (CLAUDE.md §8d, *Moderation*; §8a, *Deleting an account*).
 * Implemented in `:core:data`.
 *
 * Every call sends the [AdminToken] it is given, and nothing here keeps it. None needs a player
 * session or touches the one there is: the moderator is whoever holds the token, not a player. A
 * submission comes back as its author sees it, and with its author's opaque id besides
 * ([Submission.authorId]), which only the moderator is sent.
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

    /**
     * Adds a category (CLAUDE.md §8d, *Categories*) named [nameSr] in Serbian and [nameEn] in English,
     * each sent as typed, for the server to trim, under [id], or under the id the server makes from
     * [nameEn] when [id] is null. Returns it as the server stored it, the last in the order of
     * categories. The names and the id must pass [io.ntole.wyr.core.domain.category.CategoryRules],
     * or the server refuses them as a malformed request.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, with
     *   [io.ntole.wyr.core.domain.error.DomainError.CATEGORY_EXISTS] for an id a category has
     *   already, given or made.
     */
    public suspend fun addCategory(
        token: AdminToken,
        id: String?,
        nameSr: String,
        nameEn: String,
    ): Category

    /**
     * Sets both names of the category [id], its id never changing, and returns it as it now stands.
     * The names must pass [io.ntole.wyr.core.domain.category.CategoryRules], as for [addCategory].
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, with
     *   [io.ntole.wyr.core.domain.error.DomainError.CATEGORY_NOT_FOUND] for an id no category has.
     */
    public suspend fun renameCategory(
        token: AdminToken,
        id: String,
        nameSr: String,
        nameEn: String,
    ): Category

    /**
     * The questions players reported, most reported first, then the most lately reported, at most
     * [PAGE_SIZE] of them, read from the server every time: each as [questions] lists it, with its
     * reports counted. A retired one stays listed until its reports are dismissed. A list of
     * [PAGE_SIZE] may not be all of them.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, as [pending] does.
     */
    public suspend fun reports(token: AdminToken): List<ReportedQuestion>

    /**
     * Clears every report of the question [questionId], which leaves [reports] until a player reports
     * it again. The question stays as it stands, and hidden from each player who reported it. A
     * question with no reports is no failure.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, with
     *   [io.ntole.wyr.core.domain.error.DomainError.QUESTION_NOT_FOUND] for an id no question has.
     */
    public suspend fun dismissReports(
        token: AdminToken,
        questionId: String,
    )

    /**
     * Blocks the author [authorId] names ([ModeratedQuestion.authorId], [Submission.authorId]) from
     * submitting, and rejects each of their pending submissions with [reason], paying each one's cost
     * back as any rejection does. Their approved questions stay served. Blocking a blocked author again
     * rejects whatever is pending and changes nothing else.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, with
     *   [io.ntole.wyr.core.domain.error.DomainError.AUTHOR_NOT_FOUND] for an id no player has.
     */
    public suspend fun blockAuthor(
        token: AdminToken,
        authorId: String,
        reason: RejectionReason,
    ): AuthorBlock

    /**
     * Lets the author [authorId] names submit again; what the block rejected stays rejected. An author
     * who is not blocked is no failure.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, as [blockAuthor] does.
     */
    public suspend fun unblockAuthor(
        token: AdminToken,
        authorId: String,
    ): AuthorBlock

    /**
     * Deletes the account [account] names, on its player's request: their username and password, their
     * sessions on every device, and everything else of theirs, as their own deletion does; their
     * approved questions stay, with nobody as their author. It cannot be undone.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, with
     *   [io.ntole.wyr.core.domain.error.DomainError.PLAYER_NOT_FOUND] for an account no player has, one
     *   deleted already included.
     */
    public suspend fun deleteAccount(
        token: AdminToken,
        account: AccountRef,
    )

    public companion object {
        /**
         * The most submissions [pending] lists, questions a page of [questions] holds, and reported
         * questions [reports] lists: the most the server lists at once, so the moderator's reads are
         * few, each one a request of the address's admin budget (CLAUDE.md §8b). It is the server's
         * `WyrApi.Limits.MAX_PAGE_SIZE`, which this module cannot see (CLAUDE.md §3), so `:core:data`'s
         * tests pin the two equal.
         */
        public const val PAGE_SIZE: Int = 100
    }
}

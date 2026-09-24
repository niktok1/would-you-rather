package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission

/**
 * The players' submissions as the moderator decides them (CLAUDE.md §8d, *Moderation*). Implemented
 * in `:core:data`.
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
     * The submissions waiting for a decision, oldest first, as many as the server lists at once, read
     * from the server every time. So the first is the next to decide, and asking again once it is
     * decided gets the rest.
     */
    public suspend fun pending(token: AdminToken): List<Submission>

    /**
     * Approves the pending submission [questionId] and returns it as its author now sees it. It is
     * filed under [categories] in place of the ones its author picked, or under the author's when
     * [categories] is empty, and served from then on to every player, its author included.
     *
     * @throws IllegalArgumentException when [categories] holds [Category.OTHER], which names nothing
     *   the server can file a question under, having sent nothing.
     * @throws io.ntole.wyr.core.domain.error.WyrException on any other failure, with
     *   [io.ntole.wyr.core.domain.error.DomainError.ALREADY_DECIDED] for a submission that is not
     *   pending (decided already, by this moderator or another, or a seed) and
     *   [io.ntole.wyr.core.domain.error.DomainError.QUESTION_NOT_FOUND] for an id no question has.
     */
    public suspend fun approve(
        token: AdminToken,
        questionId: String,
        categories: Set<Category>,
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
}

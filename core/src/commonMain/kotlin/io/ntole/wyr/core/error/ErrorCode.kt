package io.ntole.wyr.core.error

import kotlinx.serialization.Serializable

/**
 * Machine-readable cause of a failed request. The client branches on this, never on
 * [ErrorDto.message].
 *
 * Same forward-compatibility contract as [io.ntole.wyr.core.question.QuestionStatus] (CLAUDE.md §5):
 * [UNKNOWN] is the default so that a code added server-side later degrades instead of failing
 * to deserialize. Never sent by the server.
 */
@Serializable
public enum class ErrorCode {
    /** No question exists with the given id. */
    QUESTION_NOT_FOUND,

    /**
     * This player has already voted on this question.
     *
     * No longer sent: answering a question again is a fresh answer, and repeating an attempt is
     * replayed (CLAUDE.md §8d). It stays in the contract because it is on the wire, and dropping a
     * member is a breaking change of its own.
     */
    ALREADY_VOTED,

    /**
     * The request body failed validation: it is malformed, or holds what no correct client sends.
     * A bug on one side, never something the player did. A submitted question the player can put
     * right is [INVALID_SUBMISSION] instead.
     */
    VALIDATION_FAILED,

    /**
     * A submitted question breaks a rule the player can break by what they type (see
     * [io.ntole.wyr.core.question.SubmitQuestionRequest]): an option blank, too long or holding a
     * control character or a line separator, or the two options the same. Sent with 422.
     */
    INVALID_SUBMISSION,

    /**
     * The player already has [io.ntole.wyr.core.api.WyrApi.Limits.MAX_PENDING_SUBMISSIONS]
     * submissions waiting for a moderator, so another is refused until one of them is decided.
     * Sent with 409.
     */
    SUBMISSION_LIMIT,

    /**
     * A moderator tried to approve or reject a submission that is not pending: a moderator decided
     * it already, or it is a seed, approved from the start. Sent with 409.
     */
    ALREADY_DECIDED,

    /**
     * A moderator asked to move a question from a status it does not stand at: to retire one that is
     * not [io.ntole.wyr.core.question.QuestionStatus.APPROVED], a retired one included, or to restore
     * one that is not [io.ntole.wyr.core.question.QuestionStatus.RETIRED]. Nothing changed. Sent with
     * 409.
     */
    WRONG_STATUS,

    /**
     * A moderator tried to add a category under an id a category has already, given or derived from
     * its English name ([io.ntole.wyr.core.category.CreateCategoryRequest]). Nothing changed. Sent with
     * 409.
     */
    CATEGORY_EXISTS,

    /** A moderator tried to rename a category no category has the id of. Sent with 404. */
    CATEGORY_NOT_FOUND,

    /**
     * A registration's username breaks the rules of [io.ntole.wyr.core.auth.RegisterRequest]: lower-cased,
     * it is too short, too long or holds a character other than `a` to `z`, `0` to `9` and `_`. The
     * player's to put right. Sent with 422.
     */
    INVALID_USERNAME,

    /**
     * A registration's password is too short or too long (see [io.ntole.wyr.core.auth.RegisterRequest]).
     * The player's to put right. Sent with 422.
     */
    INVALID_PASSWORD,

    /** Another player has the username a registration asked for, compared ignoring case. Sent with 409. */
    USERNAME_TAKEN,

    /**
     * The player registering has a username and password already. Neither can change, for now. Sent
     * with 409.
     */
    ALREADY_REGISTERED,

    /**
     * A login's username and password name no account: the username is no player's, or the password is
     * not theirs, and the answer never says which. Sent with 401, which a client must not take for an
     * expired access token: a login spends no session, so there is nothing to refresh.
     */
    INVALID_LOGIN,

    /** Caller is not authenticated, or the credential is expired. */
    UNAUTHORIZED,

    /**
     * An admin route was called without the server's admin token
     * ([io.ntole.wyr.core.api.WyrApi.Headers.ADMIN_TOKEN]), or with another. Sent with 403, never
     * 401: no player session is at fault, so a client must not refresh one or replace it.
     */
    FORBIDDEN,

    /** The supplied refresh token is unknown, already used, or expired. */
    INVALID_REFRESH_TOKEN,

    /**
     * A recovery secret no player held.
     *
     * No longer sent: the recovery secret and its routes are gone (CLAUDE.md §8a). It stays in the
     * contract because it is on the wire, and dropping a member is a breaking change of its own.
     */
    INVALID_RECOVERY_SECRET,

    /**
     * Too many requests from this player, or from this address for a caller with no session: back off
     * and retry. Sent with 429 and a `Retry-After` header, the whole seconds to wait. The refused
     * request did nothing.
     */
    RATE_LIMITED,

    /** Unexpected server-side failure. */
    INTERNAL,

    UNKNOWN,
}

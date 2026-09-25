package io.ntole.wyr.core.error

import kotlinx.serialization.Serializable

/**
 * Machine-readable cause of a failed request. The client branches on this, never on
 * [ErrorDto.message].
 *
 * Same forward-compatibility contract as [io.ntole.wyr.core.question.QuestionCategory]:
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
     * The recovery secret presented ([io.ntole.wyr.core.api.WyrApi.Paths.AUTH_RECOVER]) is none that
     * any player holds now: it was never issued, or its player has replaced it since. Sent with 401,
     * alike for every such secret. It will recover nobody, ever: a client drops it. A server whose
     * database is in memory, as the dev server's is, forgets every secret when it restarts.
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

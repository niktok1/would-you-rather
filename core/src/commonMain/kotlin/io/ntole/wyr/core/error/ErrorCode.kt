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

    /** This player has already voted on this question. */
    ALREADY_VOTED,

    /** The request body failed validation. */
    VALIDATION_FAILED,

    /** Caller is not authenticated, or the credential is expired. */
    UNAUTHORIZED,

    /** The supplied refresh token is unknown, already used, or expired. */
    INVALID_REFRESH_TOKEN,

    /** Too many requests; back off and retry. */
    RATE_LIMITED,

    /** Unexpected server-side failure. */
    INTERNAL,

    UNKNOWN,
}

package io.ntole.wyr.core.domain.error

/**
 * Domain-side failure classification.
 *
 * Mirrors the wire `ErrorCode` but is owned by the domain, so UI and use cases branch on this
 * and never import a wire type. `:core:data` translates one into the other.
 */
public enum class DomainError {
    QUESTION_NOT_FOUND,
    ALREADY_VOTED,
    UNAUTHORIZED,
    RATE_LIMITED,

    /** Request never reached the server, or its answer did not arrive whole. */
    NETWORK,

    /** Server reached and it failed, or it answered in a shape this build cannot decode. */
    SERVER,

    /** No questions left to serve locally or remotely. */
    OUT_OF_QUESTIONS,

    UNKNOWN,
}

/**
 * The single exception type crossing out of the data layer.
 *
 * [error] is what callers branch on; [message] is diagnostic only and must not be shown to a
 * player.
 */
public class WyrException(
    public val error: DomainError,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: error.name, cause)

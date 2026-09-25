package io.ntole.wyr.core.domain.error

import kotlin.time.Duration

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

    /**
     * A recovery secret no player holds now: never issued, or replaced since. It will recover nobody,
     * ever. Nothing to do with the session the device holds, if any, which stays as it is.
     */
    INVALID_RECOVERY_SECRET,
    RATE_LIMITED,

    /** A submitted question broke a rule the player can put right by editing it. */
    INVALID_SUBMISSION,

    /** The player has as many submissions waiting for a moderator as they may have at once. */
    SUBMISSION_LIMIT,

    /** A moderator tried to decide a submission that a moderator has already approved or rejected. */
    ALREADY_DECIDED,

    /**
     * A moderator tried to retire a question that is not approved, a retired one included, or to
     * restore one that is not retired. Nothing changed.
     */
    WRONG_STATUS,

    /**
     * A moderator's request did not carry the server's admin token. Nothing to do with the player's
     * session, which stays as it is.
     */
    FORBIDDEN,

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
 *
 * [retryAfter] is how long the server asked the caller to wait before trying again, which it names
 * with every [DomainError.RATE_LIMITED], or null when it named no wait.
 */
public class WyrException(
    public val error: DomainError,
    message: String? = null,
    cause: Throwable? = null,
    public val retryAfter: Duration? = null,
) : Exception(message ?: error.name, cause)

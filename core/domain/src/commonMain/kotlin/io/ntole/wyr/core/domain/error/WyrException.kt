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
    RATE_LIMITED,

    /** A submitted question broke a rule the player can put right by editing it. */
    INVALID_SUBMISSION,

    /** The player has as many submissions waiting for a moderator as they may have at once. */
    SUBMISSION_LIMIT,

    /**
     * The player has fewer points than submitting a question costs (CLAUDE.md §8c). Nothing was
     * stored or taken; answering earns the points.
     */
    NOT_ENOUGH_POINTS,

    /** A moderator tried to decide a submission that a moderator has already approved or rejected. */
    ALREADY_DECIDED,

    /**
     * A moderator tried to retire a question that is not approved, a retired one included, or to
     * restore one that is not retired. Nothing changed.
     */
    WRONG_STATUS,

    /**
     * A moderator tried to add a category under an id a category has already, given or made from the
     * English name. Nothing was added.
     */
    CATEGORY_EXISTS,

    /** A moderator tried to rename a category no category's id names. Nothing changed. */
    CATEGORY_NOT_FOUND,

    /**
     * A moderator's request did not carry the server's admin token. Nothing to do with the player's
     * session, which stays as it is.
     */
    FORBIDDEN,

    /**
     * A registration's username breaks the account rules (`AccountRules`). The player's to put right,
     * and a client checks the rules before it sends, so seeing this means the two disagree.
     */
    INVALID_USERNAME,

    /** A registration's password breaks the account rules (`AccountRules`), as [INVALID_USERNAME]. */
    INVALID_PASSWORD,

    /** Another player has the username a registration asked for, compared ignoring case. */
    USERNAME_TAKEN,

    /**
     * The player registering has an account already: a username and password never change, for now.
     * A registration sent again after its answer was lost gets this too.
     */
    ALREADY_REGISTERED,

    /**
     * A login's username and password name no account, whichever of the two is wrong. Never
     * [UNAUTHORIZED]: a mistyped password says nothing about the session this device holds, which
     * stays as it was.
     */
    INVALID_LOGIN,

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

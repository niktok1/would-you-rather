package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.error.DomainError
import kotlin.time.Duration

/** How an action ended when it did not work, as the screen that started it shows it. */
sealed interface Failure {
    /**
     * The server said no, or never answered: [error] is what the data layer made of it. [retryAfter]
     * is the wait a 429 named, and [detail] the server's diagnostic message, which never holds the
     * token: the server does not echo it.
     */
    data class Refused(
        val error: DomainError,
        val retryAfter: Duration? = null,
        val detail: String? = null,
    ) : Failure

    /** Anything else thrown, which is a bug in this app or below it, shown rather than crashing it. */
    data class Bug(
        val type: String,
        val message: String?,
    ) : Failure
}

/**
 * What [failure] means for the moderator, in a line. Every [DomainError] is named, so one added later
 * does not compile here until it has words of its own.
 */
fun describe(failure: Failure): String =
    when (failure) {
        is Failure.Refused -> describe(failure.error, failure.retryAfter)
        is Failure.Bug -> "A bug in this app: ${failure.type}" + failure.message?.let { ": $it" }.orEmpty()
    }

private fun describe(
    error: DomainError,
    retryAfter: Duration?,
): String =
    when (error) {
        DomainError.FORBIDDEN -> {
            "Wrong admin token (403): this server refused it. Check it against the server's ADMIN_TOKEN."
        }

        // A server with moderation off has no admin routes, so each is a bare 404, which is the one
        // answer the data layer cannot name (CLAUDE.md §8d, Moderation). A server older than a route
        // answers the same.
        DomainError.UNKNOWN -> {
            "Moderation is off on this server (404): it has no ADMIN_TOKEN, or runs a build without this route."
        }

        DomainError.RATE_LIMITED -> {
            val wait = retryAfter?.let { "try again in ${it.inWholeSeconds} s" } ?: "try again shortly"
            // Ten wrong tokens a minute lock an address out, the right token too (CLAUDE.md §8b).
            "Too many requests (429): $wait. Wrong tokens lock this address out for a while, the right one too."
        }

        DomainError.ALREADY_DECIDED -> {
            "Already decided (409), here or by another moderator: nothing changed."
        }

        DomainError.WRONG_STATUS -> {
            "Its status changed first (409), here or by another moderator: nothing changed."
        }

        DomainError.QUESTION_NOT_FOUND -> {
            "No such question on this server (404)."
        }

        DomainError.NETWORK -> {
            "No answer from the server. A Render service asleep takes up to a minute to wake: try again."
        }

        DomainError.SERVER -> {
            "The server failed, or answered in a shape this build cannot read."
        }

        // None of these is an answer an admin route gives.
        DomainError.UNAUTHORIZED,
        DomainError.ALREADY_VOTED,
        DomainError.INVALID_SUBMISSION,
        DomainError.SUBMISSION_LIMIT,
        DomainError.OUT_OF_QUESTIONS,
        -> {
            "Unexpected answer from the server: $error."
        }
    }

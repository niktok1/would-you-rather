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

        // A server with moderation off has no admin routes, so each is a bare 404, which the data layer
        // cannot name (CLAUDE.md §8d, Moderation), and nor a server older than a route. But a proxy's
        // own error page, a 401 without the server's body and a code newer than this build read the
        // same, so the status is left to the detail line, the client's own account of the exchange.

        DomainError.UNKNOWN -> {
            "An answer this build cannot name (its status is in the line below). A bare 404 means moderation" +
                " is off on this server (no ADMIN_TOKEN), or its build has no such route."
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

        DomainError.CATEGORY_EXISTS -> {
            "A category has that id already (409), given or made from the English name: nothing was added."
        }

        DomainError.CATEGORY_NOT_FOUND -> {
            "No such category on this server (404)."
        }

        DomainError.AUTHOR_NOT_FOUND -> {
            "No such author on this server (404): their account may have been deleted."
        }

        DomainError.PLAYER_NOT_FOUND -> {
            "No such account on this server (404): check the username or the account id. It may be deleted already."
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
        DomainError.NOT_ENOUGH_POINTS,
        DomainError.ACCOUNT_REQUIRED,
        DomainError.SUBMISSIONS_BLOCKED,
        DomainError.OUT_OF_QUESTIONS,
        DomainError.INVALID_USERNAME,
        DomainError.INVALID_PASSWORD,
        DomainError.USERNAME_TAKEN,
        DomainError.ALREADY_REGISTERED,
        DomainError.INVALID_LOGIN,
        DomainError.PLAY_GAMES_CODE_REFUSED,
        DomainError.PLAY_GAMES_UNAVAILABLE,
        DomainError.ALREADY_OWNED,
        DomainError.ITEM_NOT_FOUND,
        // The moderation app names no build, which the server never refuses as too old.
        DomainError.UPGRADE_REQUIRED,
        -> {
            "Unexpected answer from the server: $error."
        }
    }

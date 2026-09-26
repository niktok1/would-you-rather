package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Runs a network call and converts every failure into a [WyrException].
 *
 * Wrapping every data-layer call in this is what keeps `ApiException` — and with it the whole
 * wire vocabulary — from leaking upward.
 */
internal suspend fun <T> runApi(block: suspend () -> T): T =
    try {
        block()
    } catch (cancellation: CancellationException) {
        // Never translate cancellation into a domain error: doing so would make a cancelled
        // coroutine look like a failed request and break structured concurrency.
        throw cancellation
    } catch (api: ApiException) {
        // The wait the server named goes up with it: nothing above may parse the diagnostic message.
        throw WyrException(api.toDomainError(), api.message, api, retryAfter = api.retryAfter)
    } catch (other: Throwable) {
        // Nothing came back, or it did not arrive whole. A fault in the program or the VM is
        // neither, and must not be dressed up as one.
        if (!other.isRequestFailure()) throw other
        // An answer arrived and this build could not decode it: client and server disagree about
        // the contract. Like a rejected request body (VALIDATION_FAILED), that is a bug on one
        // side, and "check your connection" would send the player looking in the wrong place.
        if (other.isUndecodableBody()) throw WyrException(DomainError.SERVER, other.message, other)
        throw WyrException(DomainError.NETWORK, other.message, other)
    }

/**
 * Whether decoding a body failed. Ktor wraps the decoder's [SerializationException] in its own
 * converter exception, so the cause chain is searched (bounded, in case of a cycle). A body that
 * was not JSON at all — a captive portal's login page, say — fails before decoding and stays
 * NETWORK.
 */
private fun Throwable.isUndecodableBody(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is SerializationException }

private const val MAX_CAUSE_DEPTH = 8

/**
 * Whether this is an ordinary failed exchange, as opposed to a bug or a VM fault.
 *
 * Everywhere but the browser that means an [Exception]. Ktor's browser engines (js and wasmJs)
 * are the odd ones out: they reject a failed fetch with a bare `kotlin.Error("Fail to fetch")`,
 * so an offline web player's request would otherwise escape as an unhandled error. Only that
 * exact class is let in — its subclasses (`NotImplementedError`, `AssertionError`, and on the JVM
 * `OutOfMemoryError`) are faults that should crash, not read as "offline".
 */
private fun Throwable.isRequestFailure(): Boolean = this is Exception || this::class == Error::class

/**
 * The server's own code when it sent one, otherwise the broad class of failure the status carries.
 *
 * The fallback is for error responses the server never wrote: a proxy's 502 or 503 page is HTML,
 * yields no `ErrorDto`, and would otherwise read as UNKNOWN when it is plainly a server failure.
 *
 * A bare 401 deliberately stays UNKNOWN. UNAUTHORIZED makes the data layer throw the session away
 * and mint a new guest, orphaning the old account and its points (CLAUDE.md §8a) — and every 401
 * the server sends carries an `ErrorDto`, so one without it did not come from the server and is no
 * evidence the session is dead.
 */
internal fun ApiException.toDomainError(): DomainError =
    when {
        code != ErrorCode.UNKNOWN -> code.toDomain()
        status == HTTP_TOO_MANY_REQUESTS -> DomainError.RATE_LIMITED
        status == HTTP_UPGRADE_REQUIRED -> DomainError.UPGRADE_REQUIRED
        status in HTTP_SERVER_ERRORS -> DomainError.SERVER
        else -> DomainError.UNKNOWN
    }

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_UPGRADE_REQUIRED = 426
private val HTTP_SERVER_ERRORS = 500..599

internal fun ErrorCode.toDomain(): DomainError =
    when (this) {
        ErrorCode.QUESTION_NOT_FOUND -> DomainError.QUESTION_NOT_FOUND

        ErrorCode.ALREADY_VOTED -> DomainError.ALREADY_VOTED

        ErrorCode.UNAUTHORIZED, ErrorCode.INVALID_REFRESH_TOKEN -> DomainError.UNAUTHORIZED

        // No longer sent, and answered only to a recovery, which this build never asks for. Never
        // UNAUTHORIZED, which would throw the session away.
        ErrorCode.INVALID_RECOVERY_SECRET -> DomainError.UNKNOWN

        ErrorCode.RATE_LIMITED -> DomainError.RATE_LIMITED

        // This build is older than the server serves: only an update puts it right, which the game says
        // on a screen of its own. Never UNAUTHORIZED: it comes with a 426, and the session is fine.
        ErrorCode.UPGRADE_REQUIRED -> DomainError.UPGRADE_REQUIRED

        ErrorCode.INTERNAL -> DomainError.SERVER

        // A rejected body means client and server disagree about the contract — a bug on one
        // side, not something a player did.
        ErrorCode.VALIDATION_FAILED -> DomainError.SERVER

        // Unlike VALIDATION_FAILED, all three are the player's to put right, so they keep their own:
        // NOT_ENOUGH_POINTS by answering, which earns what submitting costs.
        ErrorCode.INVALID_SUBMISSION -> DomainError.INVALID_SUBMISSION

        ErrorCode.SUBMISSION_LIMIT -> DomainError.SUBMISSION_LIMIT

        ErrorCode.NOT_ENOUGH_POINTS -> DomainError.NOT_ENOUGH_POINTS

        // A guest's submission, which registering puts right. Never UNAUTHORIZED, which would throw
        // the session away: it comes with a 403.
        ErrorCode.ACCOUNT_REQUIRED -> DomainError.ACCOUNT_REQUIRED

        // A blocked author's submission, with a 403: never UNAUTHORIZED, which would throw the session
        // away. The Submit form says so in a few words.
        ErrorCode.SUBMISSIONS_BLOCKED -> DomainError.SUBMISSIONS_BLOCKED

        ErrorCode.ALREADY_DECIDED -> DomainError.ALREADY_DECIDED

        ErrorCode.WRONG_STATUS -> DomainError.WRONG_STATUS

        // Answered only to a moderator adding or renaming a category: each a DomainError of its own,
        // so the moderation app says which.
        ErrorCode.CATEGORY_EXISTS -> DomainError.CATEGORY_EXISTS

        ErrorCode.CATEGORY_NOT_FOUND -> DomainError.CATEGORY_NOT_FOUND

        // A moderator's block or unblock of an author no player is: a DomainError of its own, so the
        // moderation app says which.
        ErrorCode.AUTHOR_NOT_FOUND -> DomainError.AUTHOR_NOT_FOUND

        // Never UNAUTHORIZED: that would throw the player's session away over a moderator's token.
        ErrorCode.FORBIDDEN -> DomainError.FORBIDDEN

        // A registration's or a login's, each the player's to put right. INVALID_LOGIN comes with a
        // 401, and is never UNAUTHORIZED, which would throw the session away over a mistyped password.
        ErrorCode.INVALID_USERNAME -> DomainError.INVALID_USERNAME

        ErrorCode.INVALID_PASSWORD -> DomainError.INVALID_PASSWORD

        ErrorCode.USERNAME_TAKEN -> DomainError.USERNAME_TAKEN

        ErrorCode.ALREADY_REGISTERED -> DomainError.ALREADY_REGISTERED

        ErrorCode.INVALID_LOGIN -> DomainError.INVALID_LOGIN

        // Answered only to a Play Games sign-in, which no client sends yet. Never UNAUTHORIZED, which would
        // throw the session away: a refused code comes with a 422, and Google not answering with a 502.
        ErrorCode.PLAY_GAMES_CODE_REFUSED -> DomainError.UNKNOWN

        ErrorCode.PLAY_GAMES_UNAVAILABLE -> DomainError.SERVER

        ErrorCode.UNKNOWN -> DomainError.UNKNOWN
    }

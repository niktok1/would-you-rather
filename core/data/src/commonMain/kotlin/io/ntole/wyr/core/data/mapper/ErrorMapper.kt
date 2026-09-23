package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.ApiException
import kotlinx.coroutines.CancellationException

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
        throw WyrException(api.toDomainError(), api.message, api)
    } catch (other: Throwable) {
        // Nothing came back, or what came back was unreadable. A fault in the program or the VM
        // is neither, and must not be dressed up as one.
        if (!other.isRequestFailure()) throw other
        throw WyrException(DomainError.NETWORK, other.message, other)
    }

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
        status in HTTP_SERVER_ERRORS -> DomainError.SERVER
        else -> DomainError.UNKNOWN
    }

private const val HTTP_TOO_MANY_REQUESTS = 429
private val HTTP_SERVER_ERRORS = 500..599

internal fun ErrorCode.toDomain(): DomainError =
    when (this) {
        ErrorCode.QUESTION_NOT_FOUND -> DomainError.QUESTION_NOT_FOUND

        ErrorCode.ALREADY_VOTED -> DomainError.ALREADY_VOTED

        ErrorCode.UNAUTHORIZED, ErrorCode.INVALID_REFRESH_TOKEN -> DomainError.UNAUTHORIZED

        ErrorCode.RATE_LIMITED -> DomainError.RATE_LIMITED

        ErrorCode.INTERNAL -> DomainError.SERVER

        // A rejected body means client and server disagree about the contract — a bug on one
        // side, not something a player did.
        ErrorCode.VALIDATION_FAILED -> DomainError.SERVER

        ErrorCode.UNKNOWN -> DomainError.UNKNOWN
    }

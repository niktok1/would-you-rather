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
    } catch (other: Exception) {
        // Nothing came back, or what came back was unreadable.
        throw WyrException(DomainError.NETWORK, other.message, other)
    }

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

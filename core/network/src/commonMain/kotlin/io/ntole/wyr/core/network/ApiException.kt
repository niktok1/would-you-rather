package io.ntole.wyr.core.network

import io.ntole.wyr.core.error.ErrorCode
import kotlin.time.Duration

/**
 * An HTTP error response, carrying the server's own [ErrorCode] and the response's [status].
 *
 * Only a response that came back is one of these. A request that never got an answer fails with
 * whatever the engine threw, so the data layer can tell "offline" apart from "the server said no".
 *
 * [code] is [ErrorCode.UNKNOWN] when the body was not an `ErrorDto` — a proxy's error page, say —
 * which is when [status] is the only evidence of what went wrong.
 *
 * [retryAfter] is how long the response's `Retry-After` header asks the caller to wait, which the
 * server sends with every 429 (CLAUDE.md §8b), or null when it sent none or named a date instead of
 * whole seconds.
 *
 * This is a network-layer type on purpose: `:core:data` translates it into the domain's
 * `WyrException` so nothing above the data layer imports a wire type (CLAUDE.md §3).
 */
public class ApiException(
    public val code: ErrorCode,
    public val status: Int,
    message: String? = null,
    cause: Throwable? = null,
    public val retryAfter: Duration? = null,
) : Exception(message ?: code.name, cause)

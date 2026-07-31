package io.ntole.wyr.core.network

import io.ntole.wyr.core.error.ErrorCode

/**
 * A failed API call, carrying the server's own [ErrorCode].
 *
 * This is a network-layer type on purpose: `:core:data` translates it into the domain's
 * `WyrException` so nothing above the data layer imports a wire type (CLAUDE.md §3).
 */
public class ApiException(
    public val code: ErrorCode,
    public val status: Int?,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: code.name, cause)

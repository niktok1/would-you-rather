package io.ntole.wyr.core.error

import kotlinx.serialization.Serializable

/**
 * Body of any non-2xx response. The HTTP status carries the broad class of failure;
 * [code] carries the specific cause.
 *
 * [message] is diagnostic only — for logs and debugging. It is not localized and must never
 * be shown to the player or branched on; use [code] for both.
 *
 * [code] must keep its default for unknown-value coercion to work — see [ErrorCode].
 */
@Serializable
public data class ErrorDto(
    public val message: String? = null,
    public val code: ErrorCode = ErrorCode.UNKNOWN,
)

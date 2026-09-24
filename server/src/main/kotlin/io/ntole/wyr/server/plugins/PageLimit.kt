package io.ntole.wyr.server.plugins

import io.ktor.http.Parameters
import io.ntole.wyr.core.api.WyrApi

/**
 * How many items a request asks for with [WyrApi.Query.LIMIT], held between 1 and
 * [WyrApi.Limits.MAX_PAGE_SIZE], or [WyrApi.Limits.DEFAULT_PAGE_SIZE] when it asks for no number.
 * A value that is not a number is the client's mistake. Every route that takes a limit reads it here,
 * so they all hold it to the same bounds.
 */
fun Parameters.pageLimit(): Int =
    this[WyrApi.Query.LIMIT]
        ?.let { raw -> raw.toIntOrNull() ?: throw ApiFailure.validation("limit must be a number: $raw") }
        ?.coerceIn(1, WyrApi.Limits.MAX_PAGE_SIZE)
        ?: WyrApi.Limits.DEFAULT_PAGE_SIZE

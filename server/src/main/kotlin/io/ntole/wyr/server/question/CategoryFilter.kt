package io.ntole.wyr.server.question

import io.ktor.http.Parameters
import io.ntole.wyr.core.api.WyrApi

/**
 * The category ids a request filters to with [WyrApi.Query.CATEGORY]: one per repeat of the
 * parameter, and none for every category (CLAUDE.md §8d, *Categories*). Every route that filters by
 * category reads it here and checks it, in its transaction, against the categories the server has
 * (`CategoryStore.checked`), so they all refuse the same values: one that is no category's id, a
 * comma-separated list included, is 400, whatever the other values name.
 */
fun Parameters.categoryFilter(): Set<String> = getAll(WyrApi.Query.CATEGORY).orEmpty().toSet()

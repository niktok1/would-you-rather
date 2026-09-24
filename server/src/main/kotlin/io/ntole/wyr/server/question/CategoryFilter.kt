package io.ntole.wyr.server.question

import io.ktor.http.Parameters
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.server.plugins.ApiFailure

/**
 * The categories a request filters to with [WyrApi.Query.CATEGORY]: one per repeat of the parameter,
 * and none for every category (CLAUDE.md §8d, *Categories*). Every route that filters by category
 * reads it here, so they all refuse the same values.
 *
 * [QuestionCategory.UNKNOWN] is the client's decoding fallback and is never stored, so filtering by
 * it would always find nothing, and it is refused. So is a value that is not a category at all, a
 * comma-separated list included: [ApiFailure.validation], whatever the other values name.
 */
fun Parameters.categoryFilter(): Set<QuestionCategory> =
    getAll(WyrApi.Query.CATEGORY)
        .orEmpty()
        .map { raw ->
            QuestionCategory.entries.firstOrNull { it.name == raw && it != QuestionCategory.UNKNOWN }
                ?: throw ApiFailure.validation("unknown category: $raw")
        }.toSet()

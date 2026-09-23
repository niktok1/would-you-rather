package io.ntole.wyr.server.question

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.ApiFailure

/** Reading questions needs no session — only voting does. */
fun Route.questionRoutes(db: Db) {
    get(WyrApi.Paths.QUESTIONS) {
        val params = call.request.queryParameters

        val limit =
            params[WyrApi.Query.LIMIT]
                ?.let { raw ->
                    raw.toIntOrNull() ?: throw ApiFailure.validation("limit must be a number: $raw")
                }?.coerceIn(1, WyrApi.Limits.MAX_PAGE_SIZE)
                ?: WyrApi.Limits.DEFAULT_PAGE_SIZE

        val cursor =
            params[WyrApi.Query.CURSOR]?.let { raw ->
                raw.toLongOrNull() ?: throw ApiFailure.validation("malformed cursor: $raw")
            }

        // UNKNOWN is the client's decoding fallback and is never stored, so filtering by it would
        // answer an empty page with no cursor — indistinguishable from the end of the catalogue.
        val category =
            params[WyrApi.Query.CATEGORY]?.let { raw ->
                QuestionCategory.entries.firstOrNull { it.name == raw && it != QuestionCategory.UNKNOWN }
                    ?: throw ApiFailure.validation("unknown category: $raw")
            }

        call.respond(db.query { QuestionStore.page(cursor, limit, category) })
    }
}

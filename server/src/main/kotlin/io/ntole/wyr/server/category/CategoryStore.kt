package io.ntole.wyr.server.category

import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.server.db.Categories
import io.ntole.wyr.server.plugins.ApiFailure
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.select

/**
 * The categories questions are filed under (CLAUDE.md §8d, *Categories*): server data, each with a
 * stable id and a name in Serbian and in English.
 */
object CategoryStore {
    /**
     * Every category, with both its names, in the order of categories ([ids]): what
     * `GET /v1/categories` lists. Must run inside a transaction.
     */
    fun all(): List<CategoryDto> =
        Categories
            .select(Categories.id, Categories.nameSr, Categories.nameEn)
            .orderBy(Categories.createdAt to SortOrder.ASC, Categories.id to SortOrder.ASC)
            .map { row ->
                CategoryDto(id = row[Categories.id], nameSr = row[Categories.nameSr], nameEn = row[Categories.nameEn])
            }

    /**
     * Every category's id, in the order of categories: oldest first, then by id. Every list of
     * categories the server sends is in this order, a question's own included. Must run inside a
     * transaction.
     */
    fun ids(): List<String> =
        Categories
            .select(Categories.id)
            .orderBy(Categories.createdAt to SortOrder.ASC, Categories.id to SortOrder.ASC)
            .map { row -> row[Categories.id] }

    /**
     * [requested], each once, in the order of categories ([ids]), or an [ApiFailure.validation] when
     * one is no category's id: a filter, a submission or an approval naming a category the server does
     * not have, as only a client bug, or a client from before its category went away, sends. None for
     * none. Must run inside a transaction.
     *
     * It reads every category, which the server has hundreds of at most, rather than only those
     * asked for, so what a request names never sizes the statement. A plain read: nothing deletes a
     * category, so one read here is still there when the transaction writes a question under it, and
     * the foreign key on `question_categories` holds every row to one regardless.
     */
    fun checked(requested: Collection<String>): List<String> {
        if (requested.isEmpty()) return emptyList()

        val all = ids()
        val known = all.toSet()
        val unknown = requested.firstOrNull { it !in known }
        if (unknown != null) throw ApiFailure.validation("unknown category: $unknown")
        val wanted = requested.toSet()
        return all.filter { it in wanted }
    }
}

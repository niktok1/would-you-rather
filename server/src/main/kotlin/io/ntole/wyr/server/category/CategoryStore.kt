package io.ntole.wyr.server.category

import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.server.db.Categories
import io.ntole.wyr.server.plugins.ApiFailure
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

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

    /**
     * Adds [category], already checked (`checkedCreation`), added at [now], and returns it. Must run
     * inside a transaction. From its commit on it is in [all], after every category before it, and a
     * submission, an approval and a filter may name it. An id a category has already is refused with
     * 409, and nothing changes.
     *
     * The primary key, not the read, decides two creations racing for one id (CLAUDE.md §4): both find
     * no row and both insert, and the second fails on the key once the first commits. That failure is
     * not caught: Exposed rolls back and reruns the transaction, which then finds the row and refuses.
     * The read only spares an id already there a certain violation.
     */
    fun create(
        category: CategoryDto,
        now: Long = System.currentTimeMillis(),
    ): CategoryDto {
        val exists =
            Categories
                .select(Categories.id)
                .where { Categories.id eq category.id }
                .limit(1)
                .any()
        if (exists) throw ApiFailure.categoryExists(category.id)

        Categories.insert { row ->
            row[id] = category.id
            row[nameSr] = category.nameSr
            row[nameEn] = category.nameEn
            row[createdAt] = now
        }
        return category
    }

    /**
     * Sets both names of the category [category] names by id, already checked (`checkedRenaming`), and
     * returns it as it now stands. Must run inside a transaction. One update, which reads nothing
     * first: 0 rows updated means no category has the id, 404, since nothing deletes one. Of two
     * renames racing, the second waits on the first's row lock and its names are the ones that stay.
     */
    fun rename(category: CategoryDto): CategoryDto {
        val renamed =
            Categories.update({ Categories.id eq category.id }) { row ->
                row[nameSr] = category.nameSr
                row[nameEn] = category.nameEn
            }
        if (renamed == 0) throw ApiFailure.categoryNotFound(category.id)
        return category
    }
}

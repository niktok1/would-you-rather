package io.ntole.wyr.server.db

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every foreign key in [appTables] can be found through an index that leads with its column, the
 * table's key or one of its indexes. PostgreSQL indexes none by itself, so without one, deleting a row
 * the key names reads the whole referencing table for each row deleted, to check that nothing names it,
 * and `AccountDeletion` deletes players and questions. SchemaDriftTest holds the migrations to these
 * definitions, so an index here is one in the database.
 */
class ForeignKeyIndexTest {
    @Test
    fun `every foreign key but one never checked is found through an index`() {
        val unindexed =
            appTables.flatMap { table ->
                val keys = listOfNotNull(table.primaryKey?.columns?.toList()) + table.indices.map { it.columns }
                val leading = keys.map { columns -> columns.first() }.toSet()
                table.foreignKeys
                    .map { key -> key.from.first() }
                    .filter { column -> column !in leading }
                    .map { column -> "${table.tableName}.${column.name}" }
            }

        assertEquals(listOf(NEVER_CHECKED), unindexed)
    }

    private companion object {
        /**
         * Filed under a category: nothing deletes a category or changes its id (CLAUDE.md §8d,
         * *Categories*), so nothing ever checks this key, and the feed finds a question's categories by
         * the table's own key, which leads with the question.
         */
        const val NEVER_CHECKED = "question_categories.category"
    }
}

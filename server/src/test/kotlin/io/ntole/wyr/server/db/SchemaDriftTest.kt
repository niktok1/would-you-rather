package io.ntole.wyr.server.db

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The migrations and the table definitions in `Tables.kt` describe one schema (CLAUDE.md §8b), on H2
 * and, in the server-postgres CI job, on PostgreSQL.
 *
 * This is what stops a forgotten migration. The server builds its schema only from the scripts, while
 * every query is written against the definitions, so a definition changed without a script would
 * compile, pass every test that builds its tables straight from the definitions, and fail on the live
 * database.
 */
@RunWith(Parameterized::class)
internal class SchemaDriftTest(
    private val engine: SchemaTestEngine,
) {
    /**
     * Exact, names included: a later migration that drops an index or a constraint names it, and
     * must find it under that name everywhere. While V1 is the only script this also pins what makes
     * the live database's baseline safe ([Migrations.BASELINE_VERSION]): `SchemaUtils.create` built
     * that database from these definitions, and V1 builds the same schema.
     */
    @Test
    fun `the migrations build exactly the schema SchemaUtils builds from the definitions`() {
        val built =
            engine.emptyDatabase("built").serverPool().use { pool ->
                pool.inTransaction { SchemaUtils.create(*appTables) }
                schemaSnapshot(pool)
            }
        val migrated =
            engine.emptyDatabase("migrated").serverPool().use { pool ->
                Migrations.migrate(pool)
                schemaSnapshot(pool)
            }

        assertEquals(appTables.map { it.tableName }.sorted(), built.keys.map(String::lowercase).sorted())
        assertEquals(built, migrated)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<SchemaTestEngine> = SchemaTestEngine.all()
    }
}

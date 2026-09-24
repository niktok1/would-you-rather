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
     * What exposed-migration would run to make a freshly migrated database match the definitions:
     * nothing, or a definition changed without its script. The failure lists the statements, which
     * are the draft of the missing script, as `./gradlew :server:pendingMigration` prints it.
     */
    @Test
    fun `a migrated database leaves exposed-migration nothing to change`() {
        engine.emptyDatabase("drift").serverPool().use { pool ->
            Migrations.migrate(pool)

            assertEquals(
                emptyList(),
                pendingStatements(pool),
                "the table definitions changed without a migration; ./gradlew :server:pendingMigration drafts it",
            )
        }
    }

    /**
     * Exact, names included, which exposed-migration does not check: a later migration that drops an
     * index or a constraint names it, and must find it under that name everywhere. While V1 was the
     * only script this also pinned what makes the baseline of a database built before migrations safe
     * ([Migrations.BASELINE_VERSION]): `SchemaUtils.create` built such a database from the definitions
     * as they stood then, and V1 builds the same schema. Since V2 the definitions describe the latest
     * script, so MigrationsTest builds such a database by running V1 alone.
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

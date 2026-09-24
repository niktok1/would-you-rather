package io.ntole.wyr.server.db

import io.ntole.wyr.server.TestDatabaseSettings
import org.flywaydb.core.api.FlywayException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What the server's Flyway configuration ([Migrations.configuration]) refuses and allows, on H2. */
class MigrationConfigurationTest {
    @Test
    fun `the server's configuration refuses to clean`() {
        TestDatabaseSettings(h2Url("wyr-no-clean-${UUID.randomUUID()}"), user = null, password = null)
            .serverPool()
            .use { pool ->
                Migrations.migrate(pool)
                val history = history(pool)

                val refusal = assertFailsWith<FlywayException> { serverFlyway(pool).clean() }

                assertContains(refusal.message.orEmpty(), "disabled")
                assertEquals(history, history(pool))
                assertEquals(appTables.size, schemaSnapshot(pool).size, "every table is still there")
            }
    }

    /**
     * Straight through Flyway's own connections, not a pool: Flyway leaves a connection open when it
     * refuses a name, and a pool the size of the server's H2 one would have none left to read the
     * history with.
     */
    @Test
    fun `a script whose name Flyway cannot read fails the migration instead of being skipped`() {
        val database = TestDatabaseSettings(h2Url("wyr-misnamed-${UUID.randomUUID()}"), user = null, password = null)
        val flyway =
            Migrations
                .configuration()
                .locations(Migrations.LOCATION, MISNAMED)
                .dataSource(database.jdbcUrl, "", "")
                .load()

        val refusal = assertFailsWith<FlywayException> { flyway.migrate() }

        assertContains(refusal.message.orEmpty(), "V2_one_underscore.sql")
        database.serverPool().use { pool -> assertEquals(emptyList(), history(pool), "nothing ran") }
    }

    /**
     * A rollback on Render boots an older build on the database a newer one migrated. Flyway lets it
     * by default, ignoring a script it has no copy of, and the older build must keep that default:
     * refusing would leave a failed deploy with nothing to roll back to.
     */
    @Test
    fun `a build with fewer scripts still boots on a database a newer one migrated`() {
        TestDatabaseSettings(h2Url("wyr-rollback-${UUID.randomUUID()}"), user = null, password = null)
            .serverPool()
            .use { pool ->
                Migrations
                    .configuration()
                    .locations(Migrations.LOCATION, LATER)
                    .dataSource(pool)
                    .load()
                    .migrate()
                val history = history(pool)

                assertEquals(0, Migrations.migrate(pool).migrationsExecuted)
                assertEquals(history, history(pool))
            }
    }

    private companion object {
        /** Holds one script, named with a single underscore where Flyway needs two. */
        const val MISNAMED = "classpath:db/misnamed-migration"

        /** Holds one script numbered past every committed one, as a newer build's would be. */
        const val LATER = "classpath:db/later-migration"
    }
}

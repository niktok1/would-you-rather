package io.ntole.wyr.server

import io.ntole.wyr.server.db.DatabaseFactory
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.history
import io.ntole.wyr.server.db.schemaSnapshot
import io.ntole.wyr.server.db.serverPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Postgres CI job is the only thing that exercises the clean slate for real, and it cannot run
 * locally. This pins the clean itself on H2, Flyway's history included, which dropping the app
 * tables alone would leave behind for the next server to trust, and that the per-test setup
 * actually runs it.
 */
class ExternalTestDatabaseTest {
    @Test
    fun `cleaning drops every app table and the migration history`() {
        val external = ExternalTestDatabase(h2Url("wyr-test-clean"), user = null, password = null)
        TestDatabaseSettings(external.jdbcUrl, user = null, password = null).serverPool().use { pool ->
            DatabaseFactory.migrateAndSeed(pool)

            external.clean()

            assertEquals(emptyMap(), schemaSnapshot(pool))
            assertEquals(emptyList(), history(pool))
        }
    }

    @Test
    fun `a test on an external database gets it with nothing left from the last one`() {
        val url = h2Url("wyr-test-shared")
        TestDatabaseSettings(url, user = null, password = null).serverPool().use { pool ->
            // What an earlier test on the same shared database would have left behind.
            DatabaseFactory.migrateAndSeed(pool)

            val settings = testDatabaseFor("ignored") { if (it == "WYR_TEST_JDBC_URL") url else null }

            assertEquals(url, settings.jdbcUrl)
            assertEquals(emptyMap(), schemaSnapshot(pool))
            assertEquals(emptyList(), history(pool))
        }
    }

    @Test
    fun `without an external database each test gets its own H2 database`() {
        val settings = testDatabaseFor("happy-path") { null }

        assertEquals("jdbc:h2:mem:wyr-test-happy-path;DB_CLOSE_DELAY=-1", settings.jdbcUrl)
        assertNull(settings.user)
        assertNull(settings.password)
    }

    @Test
    fun `no url means the default H2 path`() {
        assertNull(ExternalTestDatabase.fromEnvironment { null })
        assertNull(ExternalTestDatabase.fromEnvironment { if (it == "WYR_TEST_JDBC_URL") " " else null })
    }

    @Test
    fun `url and credentials are read from the environment`() {
        val env =
            mapOf(
                "WYR_TEST_JDBC_URL" to "jdbc:postgresql://localhost:5432/wyr_test",
                "WYR_TEST_DB_USER" to "wyr",
                "WYR_TEST_DB_PASSWORD" to "secret",
            )

        val external = assertNotNull(ExternalTestDatabase.fromEnvironment(env::get))

        assertEquals("jdbc:postgresql://localhost:5432/wyr_test", external.jdbcUrl)
        assertEquals("wyr", external.user)
        assertEquals("secret", external.password)
    }
}

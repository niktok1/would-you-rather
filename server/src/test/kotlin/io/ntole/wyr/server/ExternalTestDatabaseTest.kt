package io.ntole.wyr.server

import io.ntole.wyr.server.db.appTables
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Postgres CI job is the only thing that exercises the clean-slate drop for real, and it
 * cannot run locally. This pins the drop itself on H2, including the foreign-key ordering that a
 * naive drop in declaration order would trip over, and that the per-test setup actually runs it.
 */
class ExternalTestDatabaseTest {
    @Test
    fun `dropping the app tables removes every one of them`() {
        val external = ExternalTestDatabase("jdbc:h2:mem:wyr-test-drop;DB_CLOSE_DELAY=-1", user = null, password = null)
        val database = Database.connect(external.jdbcUrl)
        try {
            transaction(database) { SchemaUtils.create(*appTables) }

            external.dropAppTables()

            transaction(database) {
                assertEquals(emptyList(), appTables.filter { it.exists() }.map { it.tableName })
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    @Test
    fun `a test on an external database gets it with the app tables already dropped`() {
        val url = "jdbc:h2:mem:wyr-test-shared;DB_CLOSE_DELAY=-1"
        val database = Database.connect(url)
        try {
            // What an earlier test on the same shared database would have left behind.
            transaction(database) { SchemaUtils.create(*appTables) }

            val settings = testDatabaseFor("ignored") { if (it == "WYR_TEST_JDBC_URL") url else null }

            assertEquals(url, settings.jdbcUrl)
            transaction(database) {
                assertEquals(emptyList(), appTables.filter { it.exists() }.map { it.tableName })
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
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

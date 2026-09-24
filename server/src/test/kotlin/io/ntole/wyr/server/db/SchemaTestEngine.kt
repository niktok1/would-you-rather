package io.ntole.wyr.server.db

import com.zaxxer.hikari.HikariDataSource
import io.ntole.wyr.server.ExternalTestDatabase
import io.ntole.wyr.server.TestDatabaseSettings
import io.ntole.wyr.server.config.ServerConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * An engine the schema tests run on, handing out databases that hold nothing at all, Flyway's
 * history included.
 *
 * The schema tests run on H2 always, and on the external database as well when `WYR_TEST_JDBC_URL`
 * names one ([all]), so the server-postgres CI job runs every one of them on PostgreSQL too: the live
 * database is PostgreSQL, and what the migrations build there is what matters.
 */
internal class SchemaTestEngine private constructor(
    private val label: String,
    /**
     * Whether several servers may boot on one of these databases at once. Never true of H2, even
     * named as the external database: the server's H2 database is in memory and belongs to the one
     * process that opened it, and two migrations of one H2 database at once fail inside H2.
     */
    val sharedByServers: Boolean,
    private val open: (name: String) -> TestDatabaseSettings,
) {
    /**
     * A database with nothing in it. On H2 a new one for every call; the external database is one
     * database, wiped for every call, so a test uses one at a time.
     */
    fun emptyDatabase(name: String): TestDatabaseSettings = open(name)

    override fun toString(): String = label

    companion object {
        val H2: SchemaTestEngine =
            SchemaTestEngine("H2", sharedByServers = false) { name ->
                TestDatabaseSettings(h2Url("wyr-schema-$name-${UUID.randomUUID()}"), user = null, password = null)
            }

        /** H2, and the external database as well when the environment names one. */
        fun all(env: (String) -> String? = System::getenv): List<SchemaTestEngine> =
            listOfNotNull(
                H2,
                ExternalTestDatabase.fromEnvironment(env)?.let { external ->
                    SchemaTestEngine("external database", sharedByServers = !external.jdbcUrl.startsWith("jdbc:h2:")) {
                        external.clean()
                        TestDatabaseSettings(external.jdbcUrl, external.user, external.password)
                    }
                },
            )
    }
}

/** A pool on this database with exactly the server's settings, since Flyway runs through that pool. */
internal fun TestDatabaseSettings.serverPool(): HikariDataSource =
    HikariDataSource(
        DatabaseFactory.poolConfig(
            ServerConfig.fromEnvironment { null }.copy(jdbcUrl = jdbcUrl, dbUser = user, dbPassword = password),
        ),
    )

/** Runs [block] in one Exposed transaction on this pool. */
internal fun <T> DataSource.inTransaction(block: JdbcTransaction.() -> T): T {
    val database = Database.connect(this)
    try {
        return transaction(database) { block() }
    } finally {
        TransactionManager.closeAndUnregister(database)
    }
}

/** Flyway, configured as the server configures it, on [dataSource]. */
internal fun serverFlyway(dataSource: DataSource): Flyway = Migrations.configuration().dataSource(dataSource).load()

/**
 * Fails unless the database behind [dataSource] has run every committed script from V1, each as a
 * script and none as a baseline: what an empty database's first migration records.
 */
internal fun assertRanEveryScript(dataSource: DataSource) {
    val history = history(dataSource)
    assertEquals(emptyList(), serverFlyway(dataSource).info().pending().map { it.script }, "every script ran")
    assertEquals("1 SQL", history.firstOrNull(), "from V1")
    assertEquals(emptyList(), history.filterNot { it.endsWith(" SQL") }, "each as a script, none as a baseline")
}

/**
 * What the history table records, oldest first, as (version, type): `1 SQL` for V1 run, `1 BASELINE`
 * for a database recorded at V1 without running it. Leaves out the row H2 writes to mark the table
 * created, which is no migration.
 */
internal fun history(dataSource: DataSource): List<String> =
    serverFlyway(dataSource)
        .info()
        .applied()
        .filter { it.version != null }
        .map { "${it.version} ${it.type}" }

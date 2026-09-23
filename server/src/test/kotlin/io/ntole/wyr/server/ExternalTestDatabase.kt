package io.ntole.wyr.server

import io.ntole.wyr.server.db.appTables
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * A real database for the API tests to run against instead of H2, chosen by `WYR_TEST_JDBC_URL`
 * (plus optional `WYR_TEST_DB_USER` / `WYR_TEST_DB_PASSWORD`).
 *
 * Unset is the default and what a laptop runs: every test then gets its own in-memory H2
 * database. CI sets it to a Postgres service container, because H2 only approximates the engine
 * production actually runs on.
 */
internal class ExternalTestDatabase(
    val jdbcUrl: String,
    val user: String?,
    val password: String?,
) {
    /**
     * Every test shares this one database, so a clean slate means dropping every app table before
     * the server starts; the server's own schema creation then rebuilds and reseeds them.
     */
    fun dropAppTables() {
        val database = Database.connect(url = jdbcUrl, user = user.orEmpty(), password = password.orEmpty())
        try {
            transaction(database) { SchemaUtils.drop(*appTables) }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv): ExternalTestDatabase? {
            val url = env("WYR_TEST_JDBC_URL")?.takeIf { it.isNotBlank() } ?: return null
            return ExternalTestDatabase(url, env("WYR_TEST_DB_USER"), env("WYR_TEST_DB_PASSWORD"))
        }
    }
}

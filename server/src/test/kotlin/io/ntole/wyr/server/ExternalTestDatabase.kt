package io.ntole.wyr.server

import io.ntole.wyr.server.db.Migrations

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
     * Every test shares this one database, so a clean slate means dropping everything in it before
     * the server starts, Flyway's history included: the server's migrations then rebuild the schema
     * from V1 and it reseeds. A history left behind would tell Flyway the dropped tables still exist.
     *
     * Flyway's clean drops whatever is there, whichever migration created it, and is turned on only
     * here: the server refuses it ([Migrations.configuration]).
     */
    fun clean() {
        Migrations
            .configuration()
            .dataSource(jdbcUrl, user.orEmpty(), password.orEmpty())
            .cleanDisabled(false)
            .load()
            .clean()
    }

    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv): ExternalTestDatabase? {
            val url = env("WYR_TEST_JDBC_URL")?.takeIf { it.isNotBlank() } ?: return null
            return ExternalTestDatabase(url, env("WYR_TEST_DB_USER"), env("WYR_TEST_DB_PASSWORD"))
        }
    }
}

/** The database one API test's server connects to, already clean for that test. */
internal data class TestDatabaseSettings(
    val jdbcUrl: String,
    val user: String?,
    val password: String?,
)

/**
 * Picks the database for the test named [databaseName] and hands it over clean. H2 isolates tests
 * by database name, so the default needs nothing more; a shared external database is wiped first.
 *
 * This lives here rather than inline in `runServer` so the wipe is pinned by a test. The current
 * ApiFlowTest cases happen to pass on a shared database even without it, in the order JUnit runs
 * them today, so dropping the call would stay green until a rename or a new test reordered them.
 */
internal fun testDatabaseFor(
    databaseName: String,
    env: (String) -> String? = System::getenv,
): TestDatabaseSettings {
    val external =
        ExternalTestDatabase.fromEnvironment(env)
            ?: return TestDatabaseSettings(
                jdbcUrl = "jdbc:h2:mem:wyr-test-$databaseName;DB_CLOSE_DELAY=-1",
                user = null,
                password = null,
            )
    external.clean()
    return TestDatabaseSettings(external.jdbcUrl, external.user, external.password)
}

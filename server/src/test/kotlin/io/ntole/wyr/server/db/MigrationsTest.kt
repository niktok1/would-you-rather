package io.ntole.wyr.server.db

import io.ntole.wyr.server.TestDatabaseSettings
import io.ntole.wyr.server.player.PlayerStore
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two databases a boot can find (CLAUDE.md §8b): an empty one, which runs every script, and one
 * the server built before it had migrations, the live database, which is recorded at V1 without
 * running anything. On H2 and, in the server-postgres CI job, on PostgreSQL.
 */
@RunWith(Parameterized::class)
internal class MigrationsTest(
    private val engine: SchemaTestEngine,
) {
    @Test
    fun `an empty database runs every script from V1`() {
        engine.emptyDatabase("empty").serverPool().use { pool ->
            val result = Migrations.migrate(pool)

            assertRanEveryScript(pool)
            assertEquals(history(pool).size, result.migrationsExecuted)
            assertEquals(appTables.size, schemaSnapshot(pool).size, "the scripts build every app table")
        }
    }

    /**
     * The live database's own path, taken once, by the first boot of a build with migrations. Tied
     * to V1 while V1 is the only script: see [buildAsBeforeMigrations].
     */
    @Test
    fun `a database built before migrations is recorded at V1 without running it, its data untouched`() {
        engine.emptyDatabase("before-migrations").serverPool().use { pool ->
            buildAsBeforeMigrations(pool)
            val before = pool.inTransaction { contents() }

            val result = Migrations.migrate(pool)

            assertEquals(listOf("1 BASELINE"), history(pool), "recorded at V1, and V1 never ran")
            assertEquals(0, result.migrationsExecuted)
            assertEquals(before, pool.inTransaction { contents() })
        }
    }

    @Test
    fun `a second boot runs nothing`() {
        for (build in listOf<(DataSource) -> Unit>({}, ::buildAsBeforeMigrations)) {
            engine.emptyDatabase("second-boot").serverPool().use { pool ->
                build(pool)
                Migrations.migrate(pool)
                val history = history(pool)

                assertEquals(0, Migrations.migrate(pool).migrationsExecuted)
                assertEquals(history, history(pool))
            }
        }
    }

    /**
     * Render starts a deploy's new instance before it stops the old one, and more than one instance
     * may run. On PostgreSQL, Flyway's advisory lock lets one boot create the history and run the
     * scripts while the rest wait, then find them done. Not on H2: two migrations of one H2 database at once fail
     * inside H2, but a server's H2 database is in memory and its own, so no two ever share one.
     */
    @Test
    fun `servers booting at once on an empty database run every script once`() {
        assumeTrue("an H2 database is never shared by two servers", engine.sharedByServers)
        repeat(RACES) {
            val database = engine.emptyDatabase("boot-race-empty")

            bootAtOnce(database)

            database.serverPool().use { pool -> assertRanEveryScript(pool) }
        }
    }

    /** As above, for the live database's one boot of this build, which takes the baseline. */
    @Test
    fun `servers booting at once on a database built before migrations record it at V1 once`() {
        assumeTrue("an H2 database is never shared by two servers", engine.sharedByServers)
        repeat(RACES) {
            val database = engine.emptyDatabase("boot-race-before-migrations")
            val before =
                database.serverPool().use { pool ->
                    buildAsBeforeMigrations(pool)
                    pool.inTransaction { contents() }
                }

            bootAtOnce(database)

            database.serverPool().use { pool ->
                assertEquals(listOf("1 BASELINE"), history(pool))
                assertEquals(before, pool.inTransaction { contents() })
            }
        }
    }

    /**
     * Boots [SERVERS] servers on [database] together, each through a pool of its own as separate
     * instances would, and fails unless every one of them boots.
     */
    private fun bootAtOnce(database: TestDatabaseSettings) {
        val start = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(SERVERS)
        try {
            val boots =
                List(SERVERS) {
                    threads.submit(
                        Callable {
                            database.serverPool().use { pool ->
                                start.await()
                                DatabaseFactory.migrateAndSeed(pool)
                            }
                        },
                    )
                }
            start.countDown()
            boots.forEach { it.get(BOOT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            threads.shutdownNow()
        }
    }

    /**
     * The database as the server left it before migrations: its tables built by
     * `SchemaUtils.create(*appTables)`, exactly as production's were, holding data, and no history.
     *
     * The definitions describe V1's schema only while V1 is the only script. The first later one
     * that changes a table must move this onto V1 (Flyway with `target("1")`, then drop the history
     * table), which SchemaDriftTest will have pinned to what `SchemaUtils.create` built until then,
     * and the tests that start from it must expect that script's row in the history and its change
     * to the rows.
     */
    private fun buildAsBeforeMigrations(pool: DataSource) {
        pool.inTransaction {
            SchemaUtils.create(*appTables)
            Seed.questionsIfEmpty()
            val author = PlayerStore.createGuest(refreshTokenHash = "a".repeat(64), refreshExpiresAt = 1L)
            PlayerStore.addPoints(author.id, points = 3)
            Likes.insert { row ->
                row[playerId] = author.id
                row[questionId] = "seed-1"
            }
        }
    }

    /** Every row of every app table, so a comparison shows any row lost, added or changed. */
    private fun contents(): Map<String, List<String>> =
        appTables.associate { table -> table.tableName to table.selectAll().map { it.toString() }.sorted() }

    companion object {
        private const val SERVERS = 4
        private const val RACES = 3

        /** Past Flyway's own wait for its lock, 50 tries a second apart, so Flyway gives up first. */
        private const val BOOT_TIMEOUT_SECONDS = 90L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<SchemaTestEngine> = SchemaTestEngine.all()
    }
}

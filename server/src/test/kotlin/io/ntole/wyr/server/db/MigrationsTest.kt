package io.ntole.wyr.server.db

import io.ntole.wyr.server.TestDatabaseSettings
import io.ntole.wyr.server.player.PlayerStore
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.output.MigrateResult
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The databases a boot can find (CLAUDE.md §8b): an empty one, which runs every script; one the
 * server built before it had migrations, which is recorded at V1 without running anything; and any
 * other with tables and no history, which fails the boot. On H2 and, in the server-postgres CI job,
 * on PostgreSQL.
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
     * The path of a database a server before migrations built, taken once, by the first boot of a
     * build with migrations on it. Tied to V1 while V1 is the only script: see
     * [buildAsBeforeMigrations].
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

    /**
     * What marks a database as built before migrations is V1's tables, every one: a baseline of a
     * database missing one would record it at V1 all the same.
     */
    @Test
    fun `the tables that mark a database built before migrations are the ones V1 builds`() {
        engine.emptyDatabase("v1-tables").serverPool().use { pool ->
            Migrations
                .configuration()
                .dataSource(pool)
                .target(Migrations.BASELINE_VERSION)
                .load()
                .migrate()

            assertEquals(Migrations.TABLES_BEFORE_MIGRATIONS, schemaSnapshot(pool).keys.map(String::lowercase).toSet())
        }
    }

    /**
     * Only a database built before migrations is baselined. Flyway's own baselineOnMigrate would record
     * any database with tables at V1, and a server would then boot on one missing some of V1's tables,
     * or holding none of them, and fail only at the first query that needed one.
     */
    @Test
    fun `a database with tables and no history that the server did not build fails the boot, left as it was`() {
        val builds =
            listOf<(DataSource) -> Unit>(
                { pool -> pool.inTransaction { SchemaUtils.create(Players) } },
                { pool -> pool.inTransaction { exec("CREATE TABLE not_ours (id INT)") } },
            )
        for (build in builds) {
            engine.emptyDatabase("not-built-by-the-server").serverPool().use { pool ->
                build(pool)
                val before = schemaSnapshot(pool)

                val refusal = assertFailsWith<FlywayException> { Migrations.migrate(pool) }

                assertContains(refusal.message.orEmpty(), "no schema history table")
                assertEquals(emptyList(), history(pool), "nothing recorded")
                assertEquals(before, schemaSnapshot(pool))
            }
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
     * scripts while the rest wait, then find them done. Not on H2, whose DDL commits as it goes and so
     * releases Flyway's lock there mid-script; but a server's H2 database is in memory and its own, so
     * no two ever share one. The interleaving tests below take two boots one step at a time, on H2.
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

    /** As above, for a database built before migrations, which every boot that finds it so baselines. */
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
     * Two boots on an empty database, interleaved: the second runs its whole migration at a point
     * where the first reads the schema outside Flyway's lock, each point in turn, on a database of its
     * own. That is how several instances booting at once can meet on PostgreSQL, taken one
     * interleaving at a time and with no two statements ever running at once, so it runs on H2, where
     * the reads go through JDBC's metadata ([InterleavingDataSource]).
     *
     * Flyway's own baselineOnMigrate failed one of them: the first found no history, the second
     * migrated, and the first, finding the schema no longer empty, took the baseline path and failed on
     * the history the second had written ([Migrations.migrate]).
     */
    @Test
    fun `a boot that migrates an empty database between another's reads of it leaves every script run once`() {
        assumeTrue("the reads are seen through JDBC's metadata on H2", engine === SchemaTestEngine.H2)
        forEveryInterleaving(build = {}) { pool, executed, _ ->
            assertRanEveryScript(pool)
            assertEquals(history(pool).size, executed, "each script ran in one of the two boots")
        }
    }

    /** As above, for a database built before migrations, which both boots baseline. */
    @Test
    fun `a boot that baselines a database built before migrations between another's reads records it at V1 once`() {
        assumeTrue("the reads are seen through JDBC's metadata on H2", engine === SchemaTestEngine.H2)
        forEveryInterleaving(
            build = { pool ->
                buildAsBeforeMigrations(pool)
                pool.inTransaction { contents() }
            },
        ) { pool, executed, before ->
            assertEquals(listOf("1 BASELINE"), history(pool))
            assertEquals(0, executed)
            assertEquals(before, pool.inTransaction { contents() })
        }
    }

    /**
     * Counts the points at which a boot on a database [build] made reads the schema outside Flyway's
     * lock, then, for each point on a database of its own, boots once with a second boot run whole at
     * that point, and hands [check] the database, how many scripts the two boots ran between them, and
     * what [build] returned. A failure names the point it came at.
     */
    private fun <T> forEveryInterleaving(
        build: (DataSource) -> T,
        check: (pool: DataSource, executed: Int, built: T) -> Unit,
    ) {
        val points =
            engine.emptyDatabase("interleaving").serverPool().use { pool ->
                build(pool)
                InterleavingDataSource(pool, at = 0) {}.also { Migrations.migrate(it) }.lookups
            }
        assertTrue(points > 0, "a boot reads the schema")

        for (point in 1..points) {
            try {
                interleaveAt(point, build, check)
            } catch (failure: Throwable) {
                throw AssertionError("with the other boot run at point $point of $points", failure)
            }
        }
    }

    private fun <T> interleaveAt(
        point: Int,
        build: (DataSource) -> T,
        check: (pool: DataSource, executed: Int, built: T) -> Unit,
    ) {
        val database = engine.emptyDatabase("interleaving-$point")
        database.serverPool().use { firstPool ->
            database.serverPool().use { secondPool ->
                val built = build(firstPool)
                var second: MigrateResult? = null
                val first =
                    InterleavingDataSource(firstPool, at = point) {
                        second = inAnotherThread { Migrations.migrate(secondPool) }
                    }

                val executed = Migrations.migrate(first).migrationsExecuted

                assertTrue(first.interleaved, "the other boot ran")
                check(firstPool, executed + checkNotNull(second).migrationsExecuted, built)
            }
        }
    }

    private fun <T> inAnotherThread(block: () -> T): T {
        val thread = Executors.newSingleThreadExecutor()
        try {
            return thread.submit(Callable(block)).get(BOOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            thread.shutdownNow()
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
     * `SchemaUtils.create(*appTables)`, exactly as every server before this one built its own,
     * holding data, and no history.
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

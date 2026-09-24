package io.ntole.wyr.server.db

import io.ntole.wyr.server.TestDatabaseSettings
import io.ntole.wyr.server.player.PlayerStore
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.output.MigrateResult
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
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
 * server built before it had migrations, which is recorded at V1 without running it and then takes
 * every later script; and any other with tables and no history, which fails the boot. On H2 and, in
 * the server-postgres CI job, on PostgreSQL.
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
     * build with migrations on it: recorded at V1, then taken through every later script.
     */
    @Test
    fun `a database built before migrations is recorded at V1 without running it, then migrated, its data kept`() {
        engine.emptyDatabase("before-migrations").serverPool().use { pool ->
            buildAsBeforeMigrations(pool)
            val before = pool.inTransaction { contents() }

            val result = Migrations.migrate(pool)

            assertEquals(BASELINED_HISTORY, history(pool), "recorded at V1, V1 never ran, and every later script did")
            assertEquals(BASELINED_HISTORY.size - 1, result.migrationsExecuted)
            assertEquals(afterLaterScripts(before), pool.inTransaction { contents() })
        }
    }

    /**
     * The path the production database takes (CLAUDE.md §8b): the boot of a build whose only script
     * was V1 recorded it at V1, and the first boot of a later build runs what came after, on the rows
     * its players wrote in between.
     */
    @Test
    fun `a database an earlier boot recorded at V1 takes every later script, its data kept`() {
        engine.emptyDatabase("recorded-at-v1").serverPool().use { pool ->
            buildAsBeforeMigrations(pool)
            serverFlyway(pool).baseline()
            val before = pool.inTransaction { contents() }

            val result = Migrations.migrate(pool)

            assertEquals(BASELINED_HISTORY, history(pool))
            assertEquals(BASELINED_HISTORY.size - 1, result.migrationsExecuted)
            assertEquals(afterLaterScripts(before), pool.inTransaction { contents() })
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
                assertEquals(BASELINED_HISTORY, history(pool))
                assertEquals(afterLaterScripts(before), pool.inTransaction { contents() })
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
            assertEquals(BASELINED_HISTORY, history(pool))
            assertEquals(BASELINED_HISTORY.size - 1, executed, "each later script ran in one boot, V1 in neither")
            assertEquals(afterLaterScripts(before), pool.inTransaction { contents() })
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
     * The database as the server left it before migrations: V1's schema, which is exactly what
     * `SchemaUtils.create(*appTables)` built on every server before this one, holding data, and no
     * history.
     *
     * Built by running V1 alone and then dropping the history, not by `SchemaUtils.create`: the table
     * definitions have described the latest script's schema since V2, and V1's only while it was the
     * only script, which is when SchemaDriftTest pinned V1 to what `SchemaUtils.create` built. The rows
     * are written through the stores, which name only V1's columns in what they write here, as an older
     * build's did; one that named a later column would fail here, on a table without it.
     */
    private fun buildAsBeforeMigrations(pool: DataSource) {
        val v1 =
            Migrations
                .configuration()
                .dataSource(pool)
                .target(Migrations.BASELINE_VERSION)
                .load()
        v1.migrate()
        pool.inTransaction {
            // Flyway quotes its own table's name, in lower case, on H2 as on PostgreSQL.
            exec("DROP TABLE \"${v1.configuration.table}\"")
            Seed.questionsIfEmpty()
            val author = PlayerStore.createGuest(refreshTokenHash = "a".repeat(64), refreshExpiresAt = 1L)
            PlayerStore.addPoints(author.id, points = 3)
            Likes.insert { row ->
                row[playerId] = author.id
                row[questionId] = "seed-1"
            }
        }
    }

    /**
     * Every row of every app table, each column by its lower-case name, so a comparison shows any row
     * lost, added or changed. Read with `SELECT *` rather than through the table definitions, which
     * name columns a database built before migrations does not have yet.
     */
    private fun JdbcTransaction.contents(): Contents =
        appTables.associate { table ->
            val rows =
                exec("SELECT * FROM ${table.tableName}") { result ->
                    val metadata = result.metaData
                    val columns = (1..metadata.columnCount).associateBy { metadata.getColumnLabel(it).lowercase() }
                    buildList {
                        while (result.next()) add(columns.mapValues { (_, at) -> result.getString(at) })
                    }
                }.orEmpty()
            table.tableName to rows.canonical()
        }

    /**
     * [before], a database's contents at V1, as the scripts after V1 leave them. V2 gives every player
     * no previous refresh token and changes nothing else. A later script that changes the rows already
     * there adds what it does to them here.
     */
    private fun afterLaterScripts(before: Contents): Contents =
        before.mapValues { (table, rows) ->
            val added = if (table == Players.tableName) PREVIOUS_REFRESH_TOKEN_COLUMNS else emptyList()
            rows.map { row -> row + added.associateWith { null } }.canonical()
        }

    /** Each row with its columns in name order, and the rows in the order that gives them. */
    private fun List<Map<String, String?>>.canonical(): List<Map<String, String?>> =
        map { row -> row.toSortedMap() }.sortedBy { row -> row.toString() }

    companion object {
        private const val SERVERS = 4
        private const val RACES = 3

        /**
         * The history of a database built before migrations once a boot has migrated it: V1 recorded
         * without running it, then every later script run.
         */
        private val BASELINED_HISTORY = listOf("1 BASELINE", "2 SQL")

        /** The columns V2 adds to players, empty in every row already there. */
        private val PREVIOUS_REFRESH_TOKEN_COLUMNS =
            listOf(
                "previous_refresh_token_hash",
                "previous_refresh_token_expires_at",
                "previous_refresh_token_rotated_at",
            )

        /** Past Flyway's own wait for its lock, 50 tries a second apart, so Flyway gives up first. */
        private const val BOOT_TIMEOUT_SECONDS = 90L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<SchemaTestEngine> = SchemaTestEngine.all()
    }
}

/** Every app table's rows, by table name, each row its columns by name, as [MigrationsTest] compares them. */
private typealias Contents = Map<String, List<Map<String, String?>>>

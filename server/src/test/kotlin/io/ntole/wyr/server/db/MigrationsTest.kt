package io.ntole.wyr.server.db

import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.server.TestDatabaseSettings
import io.ntole.wyr.server.auth.SessionStore
import io.ntole.wyr.server.player.PlayerStore
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.output.MigrateResult
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.sql.Connection
import java.util.UUID
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
     * One path the production database can take (CLAUDE.md §8b), the test above being the other: the
     * boot of a build whose only script was V1 recorded it at V1, and the first boot of a later build
     * runs what came after, on the rows its players wrote in between.
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
     * The path the production database takes when every build with a script is deployed in turn
     * (CLAUDE.md §8b): recorded at V1, then each later script run by a boot of its own, the last by
     * this build's, on the rows written before it. Production is at V4 (`d4a9dbf`), and its next
     * deploy runs every later script in one boot, which the tests above take.
     */
    @Test
    fun `a database each build migrated in turn takes this build's scripts, its data kept`() {
        engine.emptyDatabase("one-build-at-a-time").serverPool().use { pool ->
            buildAsBeforeMigrations(pool)
            val before = pool.inTransaction { contents() }
            serverFlyway(pool).baseline()
            // By each script's own version, not its place in the list: the versions have a gap (V11 to
            // V14 are another branch's), and Flyway runs whatever is there in order.
            val versions = BASELINED_HISTORY.map { entry -> entry.substringBefore(' ') }
            for (index in 1 until versions.lastIndex) {
                val version = versions[index]
                Migrations
                    .configuration()
                    .dataSource(pool)
                    .target(version)
                    .load()
                    .migrate()
                assertEquals(BASELINED_HISTORY.take(index + 1), history(pool), "the build whose latest is V$version")
            }

            val result = Migrations.migrate(pool)

            assertEquals(BASELINED_HISTORY, history(pool))
            assertEquals(1, result.migrationsExecuted, "only the last script")
            assertEquals(afterLaterScripts(before), pool.inTransaction { contents() })
        }
    }

    /**
     * V4 on a database the builds before it served (CLAUDE.md §8a, *Sessions*): each player's refresh
     * token moves into a session of its own, the previous one V2 keeps and its grace included, so every
     * client refreshes across the deploy as before it, now from its session. From V2, where production
     * may take V3 and V4 in one boot, and from V3, where the build before this one left it.
     */
    @Test
    fun `V4 opens a session for every player holding a refresh token, so each refreshes as before it`() {
        for (from in listOf("2", "3")) {
            engine.emptyDatabase("sessions-backfill-from-v$from").serverPool().use { pool ->
                Migrations
                    .configuration()
                    .dataSource(pool)
                    .target(from)
                    .load()
                    .migrate()
                val (refreshing, displaced) =
                    pool.inTransaction {
                        playerAsMintedBefore(refreshTokenHash = "current") to
                            playerAsMintedBefore(refreshTokenHash = "rotated", previousRefreshTokenHash = "displaced")
                    }

                Migrations.migrate(pool)

                pool.inTransaction {
                    assertEquals(
                        setOf(refreshing, displaced),
                        Sessions.selectAll().map { it[Sessions.playerId] }.toSet(),
                        "from V$from",
                    )
                    assertEquals(
                        refreshing,
                        SessionStore.rotate("current", "after-current", Long.MAX_VALUE, graceMillis = null)?.playerId,
                        "from V$from",
                    )
                    assertEquals(
                        displaced,
                        SessionStore
                            .rotate(
                                "displaced",
                                "after-displaced",
                                Long.MAX_VALUE,
                                graceMillis = null,
                            )?.playerId,
                        "from V$from",
                    )
                }
            }
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
     * instances would, and fails unless every one of them boots. They seed the first seeds alone,
     * which a database built before migrations already holds, so the seed writes nothing there and
     * the database holds what the scripts leave ([afterLaterScripts]).
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
                                DatabaseFactory.migrateAndSeed(pool, TEST_SEEDS)
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
     * are written through the stores where what those write names only V1's columns, as an older build's
     * did; one that named a later column would fail here, on a table without it. The player is inserted
     * as an older build minted one, with its refresh token in its own row, since a mint now opens a
     * session, in a table V1 does not have, and the seeds as the builds before V6 wrote them
     * ([seedAsBefore]), since the seed now files them under categories V1 has no table for.
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
            seedAsBefore()
            val author = playerAsMintedBefore(refreshTokenHash = "a".repeat(64), refreshExpiresAt = 1L)
            PlayerStore.addPoints(author, points = 3)
            // Plain SQL: Tables.kt names no likes table since V10 replaced it with reactions.
            exec("INSERT INTO likes (player_id, question_id) VALUES ('$author', 'seed-1')")
            // Plain SQL, as a build before V16 answered: V1's columns alone.
            exec(
                "INSERT INTO votes (player_id, question_id, side, created_at, answered_at, answered_in_cycle, " +
                    "attempt_id) VALUES ('$author', 'seed-1', 'A', 1, 1, 1, 'attempt-before')",
            )
        }
    }

    /**
     * A guest as a build from before sessions minted one, its refresh token in the players row, as
     * [previousRefreshTokenHash] too where one is given, which a build from V2 on had once the player
     * refreshed. Names no column V1 lacks unless it is given one of V2's. Returns the player's id.
     */
    private fun JdbcTransaction.playerAsMintedBefore(
        refreshTokenHash: String,
        refreshExpiresAt: Long = Long.MAX_VALUE,
        previousRefreshTokenHash: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        Players.insert { row ->
            row[Players.id] = id
            row[createdAt] = System.currentTimeMillis()
            row[totalPoints] = 0
            row[answersGiven] = 0
            row[Players.refreshTokenHash] = refreshTokenHash
            row[refreshTokenExpiresAt] = refreshExpiresAt
            row[currentCycle] = Players.FIRST_CYCLE
            if (previousRefreshTokenHash != null) {
                row[Players.previousRefreshTokenHash] = previousRefreshTokenHash
                row[previousRefreshTokenExpiresAt] = Long.MAX_VALUE
                row[previousRefreshTokenRotatedAt] = System.currentTimeMillis()
            }
        }
        return id
    }

    /**
     * Every row of every app table the database has, and of every table a script since dropped
     * ([DROPPED_TABLES]), each column by its lower-case name, so a comparison shows any row lost, added
     * or changed, and any table added or dropped. Read with `SELECT *` rather than through the table
     * definitions, which name columns and tables a database built before migrations does not have yet,
     * and no longer name those it had.
     */
    private fun JdbcTransaction.contents(): Contents {
        val present = schemaTables()
        val tables = appTables.map { it.tableName } + DROPPED_TABLES
        return tables.filter { it in present }.associateWith { table ->
            val rows =
                exec("SELECT * FROM $table") { result ->
                    val metadata = result.metaData
                    val columns = (1..metadata.columnCount).associateBy { metadata.getColumnLabel(it).lowercase() }
                    buildList {
                        while (result.next()) add(columns.mapValues { (_, at) -> result.getString(at) })
                    }
                }.orEmpty()
            rows.canonical()
        }
    }

    /** The tables of the connection's own schema, by lower-case name, as [Migrations] reads them. */
    private fun JdbcTransaction.schemaTables(): Set<String> {
        val jdbc = connection.connection as Connection
        return jdbc.metaData.getTables(jdbc.catalog, jdbc.schema, "%", null).use { rows ->
            buildSet { while (rows.next()) add(rows.getString("TABLE_NAME").lowercase()) }
        }
    }

    /**
     * [before], a database's contents at V1, as the scripts after V1 leave them. V2 gives every player
     * no previous refresh token and V3 retires no question. V4 gives nobody a recovery secret, and every
     * player who holds a refresh token a session holding it, under the player's own id and creation
     * time, and marks their row, the mirror, with that session's token. V5 leaves every player a guest,
     * with no username and no password. V6 adds the first categories ([Seed.CATEGORIES]) and files what
     * was under RANDOM under ABSURD instead. V7 gives every question a cost of 0. V8 gives every
     * question no made-up votes, then each seed those `Seed` gives it, and V9 each seed the Serbian
     * options `Seed` gives it. V10 moves every like into reactions, as a like, and drops likes. V15
     * adds the Home screen's two counts, each at 0, V16 gives every vote no answer time, and V17 and
     * V18 add no push token and no Play Games link. None changes anything else. A later script that changes the rows already there adds what it does to
     * them here.
     */
    private fun afterLaterScripts(before: Contents): Contents {
        val widened =
            before.mapValues { (table, rows) ->
                val added = ADDED_COLUMNS[table].orEmpty()
                val defaulted = DEFAULTED_COLUMNS[table].orEmpty()
                rows.map { row -> row + added.associateWith { null } + defaulted }
            }
        val players = widened.getValue(Players.tableName)
        val sessions =
            players
                .filter { player -> player["refresh_token_hash"] != null && player["refresh_token_expires_at"] != null }
                .map { player ->
                    mapOf(
                        "id" to player["id"],
                        "player_id" to player["id"],
                        "refresh_token_hash" to player["refresh_token_hash"],
                        "refresh_token_expires_at" to player["refresh_token_expires_at"],
                        "previous_refresh_token_hash" to player["previous_refresh_token_hash"],
                        "previous_refresh_token_expires_at" to player["previous_refresh_token_expires_at"],
                        "previous_refresh_token_rotated_at" to player["previous_refresh_token_rotated_at"],
                        "created_at" to player["created_at"],
                    )
                }
        val marked =
            players.map { player ->
                val session = sessions.singleOrNull { it["player_id"] == player["id"] }
                player + ("mirrored_refresh_token_hash" to session?.get("refresh_token_hash"))
            }
        val categories =
            Seed.CATEGORIES.map { category ->
                mapOf(
                    "id" to category.id,
                    "name_sr" to category.nameSr,
                    "name_en" to category.nameEn,
                    "created_at" to category.createdAt.toString(),
                )
            }
        val seeds = Seed.SEEDS.toMap()
        val questions =
            widened.getValue(Questions.tableName).map { row ->
                val seed = seeds[row["id"]] ?: return@map row
                row +
                    mapOf(
                        "base_votes_a" to "${seed.votes.first}",
                        "base_votes_b" to "${seed.votes.second}",
                        "option_a" to seed.optionA,
                        "option_b" to seed.optionB,
                    )
            }
        val filings =
            widened.getValue(QuestionCategories.tableName).map { row ->
                if (row["category"] == "RANDOM") row + ("category" to Seed.ABSURD) else row
            }
        val reactions = widened.getValue(LIKES).map { like -> like + ("reaction" to Reaction.LIKE.name) }
        return (
            widened - LIKES +
                mapOf(
                    Reactions.tableName to reactions,
                    Players.tableName to marked,
                    Sessions.tableName to sessions,
                    Categories.tableName to categories,
                    QuestionCategories.tableName to filings,
                    Questions.tableName to questions,
                    HomePicks.tableName to NO_HOME_PICKS_YET,
                    PushTokens.tableName to emptyList(),
                    Identities.tableName to emptyList(),
                )
        ).mapValues { (_, rows) -> rows.canonical() }
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
        private val BASELINED_HISTORY =
            listOf(
                "1 BASELINE",
                "2 SQL",
                "3 SQL",
                "4 SQL",
                "5 SQL",
                "6 SQL",
                "7 SQL",
                "8 SQL",
                "9 SQL",
                "10 SQL",
                "15 SQL",
                "16 SQL",
                "17 SQL",
                "18 SQL",
            )

        /** The Home screen's two counts as V15 writes them, as JDBC reads them back as strings. */
        private val NO_HOME_PICKS_YET =
            listOf(mapOf("side" to "A", "picks" to "0"), mapOf("side" to "B", "picks" to "0"))

        /** V1's table of likes, which V10 replaced with reactions, so Tables.kt no longer names it. */
        private const val LIKES = "likes"

        /** The tables a script after V1 dropped, each by name, which [contents] reads where they are. */
        private val DROPPED_TABLES = listOf(LIKES)

        /**
         * The columns the scripts after V1 add, by table, empty in every row already there but for
         * the players' mark, which V4 then sets ([afterLaterScripts]): V2's previous refresh token,
         * V4's mark and recovery secret and V5's username and password hash on players, and V3's
         * retirement on questions, and V16's answer time on votes.
         */
        private val ADDED_COLUMNS =
            mapOf(
                Players.tableName to
                    listOf(
                        "previous_refresh_token_hash",
                        "previous_refresh_token_expires_at",
                        "previous_refresh_token_rotated_at",
                        "mirrored_refresh_token_hash",
                        "recovery_secret_hash",
                        "username",
                        "password_hash",
                    ),
                Questions.tableName to listOf("retired_at"),
                Votes.tableName to listOf("answer_millis"),
            )

        /**
         * The columns the scripts after V1 add with a default, by table, each at that default in every
         * row already there, as JDBC reads it back as a string: V7's cost and V8's made-up votes on
         * questions, before V8 gives the seeds theirs ([afterLaterScripts]).
         */
        private val DEFAULTED_COLUMNS =
            mapOf(
                Questions.tableName to mapOf("submission_cost" to "0", "base_votes_a" to "0", "base_votes_b" to "0"),
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

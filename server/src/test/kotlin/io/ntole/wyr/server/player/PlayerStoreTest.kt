package io.ntole.wyr.server.player

import com.zaxxer.hikari.HikariDataSource
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.db.DatabaseFactory
import io.ntole.wyr.server.db.Players
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PlayerStoreTest {
    /**
     * Holds the first award's transaction open while the second one starts, so the second cannot
     * see the first's point until it commits. A read-then-write would start from the stale total
     * and overwrite the first point with its own.
     */
    @Test
    fun `two awards racing for the same player both count`() {
        // READ COMMITTED by hand, not taken from the server, so this keeps telling an increment
        // from a read-then-write whatever the server's level becomes. Under REPEATABLE_READ the
        // database refuses the second write and Exposed retries it, which rescues both alike.
        val url = h2Url("wyr-player-store-rc")
        val database = connect(url, Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer() }

        val (first, second) =
            race(
                url,
                database,
                { PlayerStore.addPoints(player.id, points = 1) },
                { PlayerStore.addPoints(player.id, points = 1) },
            )

        assertEquals(1, first)
        assertEquals(2, second, "the second award must build on the first")
        assertEquals(2, transaction(database) { PlayerStore.find(player.id)?.totalPoints })
    }

    @Test
    fun `a burst of awards for one player all count at the server's isolation level`() {
        // DatabaseFactory's own pool settings, so this pins the level production runs at. Under
        // REPEATABLE_READ every queued award is refused, and Exposed's 3 attempts run out for most.
        val url = h2Url("wyr-player-store-burst")
        val settings =
            DatabaseFactory.poolConfig(ServerConfig.fromEnvironment { null }.copy(jdbcUrl = url)).apply {
                // Wide enough for every award to hold a connection at once. The rest is the server's.
                maximumPoolSize = BURST
            }

        HikariDataSource(settings).use { dataSource ->
            val database = Database.connect(dataSource)
            val player = transaction(database) { createPlayer() }

            val totals = race(url, database, *Array(BURST) { { PlayerStore.addPoints(player.id, points = 1) } })

            assertEquals((1..BURST).toList(), totals.sorted(), "each award must build on every one before it")
            assertEquals(BURST, transaction(database) { PlayerStore.find(player.id)?.totalPoints })
        }
    }

    @Test
    fun `two refreshes racing with the same token let exactly one through`() {
        // READ COMMITTED by hand for the same reason as the awards: only here does a rotation by
        // id alone let both through. Under REPEATABLE_READ the second is refused, and its retry no
        // longer finds the token.
        val url = h2Url("wyr-player-store-refresh")
        val database = connect(url, Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "spent") }

        val (first, second) =
            race(
                url,
                database,
                { PlayerStore.rotateRefreshToken("spent", newHash = "first", expiresAt = Long.MAX_VALUE) },
                { PlayerStore.rotateRefreshToken("spent", newHash = "second", expiresAt = Long.MAX_VALUE) },
            )

        assertEquals(player.id, first?.id)
        assertNull(second, "the token was already spent by the first refresh")
        assertEquals("first", storedRefreshHash(database, player.id), "the first refresh's session must stay live")
    }

    @Test
    fun `an expired refresh token does not rotate`() {
        val database = connect(h2Url("wyr-player-store-expired"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "stale", refreshExpiresAt = 1_000L) }

        val rotated =
            transaction(database) {
                PlayerStore.rotateRefreshToken("stale", newHash = "fresh", expiresAt = Long.MAX_VALUE, now = 1_000L)
            }

        assertNull(rotated)
        assertEquals("stale", storedRefreshHash(database, player.id))
    }

    /**
     * Runs each of [blocks] in a transaction of its own, with the first held open until every
     * other one is queued behind its row lock, and returns their results in order.
     */
    private fun <T> race(
        url: String,
        database: Database,
        vararg blocks: () -> T,
    ): List<T> {
        val firstHasWritten = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(blocks.size)
        try {
            val first =
                pool.submit(
                    Callable {
                        transaction(database) {
                            blocks.first()().also {
                                firstHasWritten.countDown()
                                releaseFirst.await()
                            }
                        }
                    },
                )
            assertTrue(firstHasWritten.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the first transaction never ran")

            val rest = blocks.drop(1).map { block -> pool.submit(Callable { transaction(database) { block() } }) }
            awaitSessionsWaitingOnALock(url, count = rest.size)
            releaseFirst.countDown()

            return (listOf(first) + rest).map { result -> result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
        }
    }

    /**
     * Returns once [count] sessions are queued behind another's row lock: every later transaction
     * waiting on the first. Releasing the first any sooner could let one start after the first
     * had committed, and then the race the test is for would never happen.
     */
    private fun awaitSessionsWaitingOnALock(
        url: String,
        count: Int,
    ) {
        DriverManager.getConnection(url).use { connection ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (System.nanoTime() < deadline) {
                connection.createStatement().use { statement ->
                    statement.executeQuery(BLOCKED_SESSIONS).use { rows ->
                        rows.next()
                        if (rows.getInt(1) >= count) return
                    }
                }
                Thread.sleep(POLL_MILLIS)
            }
        }
        fail("fewer than $count transactions waited on the first, so the race did not happen")
    }

    /** Creates the players table and one player in it. Must run inside a transaction. */
    private fun createPlayer(
        refreshTokenHash: String = "unused",
        refreshExpiresAt: Long = Long.MAX_VALUE,
    ): PlayerStore.Player {
        SchemaUtils.create(Players)
        return PlayerStore.createGuest(refreshTokenHash, refreshExpiresAt)
    }

    private fun h2Url(databaseName: String) = "jdbc:h2:mem:$databaseName;DB_CLOSE_DELAY=-1"

    private fun connect(
        url: String,
        isolationLevel: Int,
    ): Database =
        Database.connect(
            url = url,
            driver = "org.h2.Driver",
            databaseConfig = DatabaseConfig { defaultIsolationLevel = isolationLevel },
        )

    private fun storedRefreshHash(
        database: Database,
        playerId: String,
    ): String? =
        transaction(database) {
            Players
                .select(Players.refreshTokenHash)
                .where { Players.id eq playerId }
                .single()[Players.refreshTokenHash]
        }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val POLL_MILLIS = 5L
        const val BURST = 8
        const val BLOCKED_SESSIONS = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL"
    }
}

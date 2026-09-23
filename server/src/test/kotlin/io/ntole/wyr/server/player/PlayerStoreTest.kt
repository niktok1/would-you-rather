package io.ntole.wyr.server.player

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
    @Test
    fun `two awards racing for the same player both count`() {
        // READ COMMITTED, Postgres's own default, rather than the server's REPEATABLE_READ. Under
        // REPEATABLE_READ the database refuses the second write and Exposed retries it, which
        // rescues a read-then-write as well, so only this level tells the two apart.
        raceTwoAwards("wyr-player-store-rc", Connection.TRANSACTION_READ_COMMITTED)
    }

    @Test
    fun `two awards racing for the same player both count at the server's isolation level`() {
        // Pins the path production takes: the second award is refused and retried, not failed.
        raceTwoAwards("wyr-player-store-rr", Connection.TRANSACTION_REPEATABLE_READ)
    }

    @Test
    fun `two refreshes racing with the same token let exactly one through`() {
        // READ COMMITTED for the same reason as the awards: a rotation by id alone lets both through
        // only here. Under REPEATABLE_READ the second is refused and its retry no longer finds the token.
        val url = h2Url("wyr-player-store-refresh")
        val database = connect(url, Connection.TRANSACTION_READ_COMMITTED)
        val player =
            transaction(database) {
                SchemaUtils.create(Players)
                PlayerStore.createGuest(refreshTokenHash = "spent", refreshExpiresAt = Long.MAX_VALUE)
            }

        val (first, second) =
            race(
                url,
                database,
                first = { PlayerStore.rotateRefreshToken("spent", newHash = "first", expiresAt = Long.MAX_VALUE) },
                second = { PlayerStore.rotateRefreshToken("spent", newHash = "second", expiresAt = Long.MAX_VALUE) },
            )

        assertEquals(player.id, first?.id)
        assertNull(second, "the token was already spent by the first refresh")
        assertEquals("first", storedRefreshHash(database, player.id), "the first refresh's session must stay live")
    }

    @Test
    fun `an expired refresh token does not rotate`() {
        val database = connect(h2Url("wyr-player-store-expired"), Connection.TRANSACTION_READ_COMMITTED)
        val player =
            transaction(database) {
                SchemaUtils.create(Players)
                PlayerStore.createGuest(refreshTokenHash = "stale", refreshExpiresAt = 1_000L)
            }

        val rotated =
            transaction(database) {
                PlayerStore.rotateRefreshToken("stale", newHash = "fresh", expiresAt = Long.MAX_VALUE, now = 1_000L)
            }

        assertNull(rotated)
        assertEquals("stale", storedRefreshHash(database, player.id))
    }

    /**
     * Holds the first award's transaction open while the second one starts, so the second cannot
     * see the first's point until it commits. A read-then-write would start from the stale total
     * and overwrite the first point with its own.
     */
    private fun raceTwoAwards(
        databaseName: String,
        isolationLevel: Int,
    ) {
        val url = h2Url(databaseName)
        val database = connect(url, isolationLevel)
        val player =
            transaction(database) {
                SchemaUtils.create(Players)
                PlayerStore.createGuest(refreshTokenHash = databaseName, refreshExpiresAt = Long.MAX_VALUE)
            }

        val (first, second) =
            race(
                url,
                database,
                first = { PlayerStore.addPoints(player.id, points = 1) },
                second = { PlayerStore.addPoints(player.id, points = 1) },
            )

        assertEquals(1, first)
        assertEquals(2, second, "the second award must build on the first")
        assertEquals(2, transaction(database) { PlayerStore.find(player.id)?.totalPoints })
    }

    /**
     * Runs [first] and [second] in transactions of their own, with [first] held open until
     * [second] is queued behind its row lock, and returns both results.
     */
    private fun <T> race(
        url: String,
        database: Database,
        first: () -> T,
        second: () -> T,
    ): Pair<T, T> {
        val firstHasWritten = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val firstResult =
                pool.submit(
                    Callable {
                        transaction(database) {
                            first().also {
                                firstHasWritten.countDown()
                                releaseFirst.await()
                            }
                        }
                    },
                )
            assertTrue(firstHasWritten.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the first transaction never ran")

            val secondResult = pool.submit(Callable { transaction(database) { second() } })
            awaitSessionWaitingOnALock(url)
            releaseFirst.countDown()

            return firstResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) to
                secondResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
        }
    }

    /**
     * Returns once some session is queued behind another's row lock: the second transaction
     * waiting on the first. Releasing the first any sooner could let the second start after the
     * first had committed, and then the race the test is for would never happen.
     */
    private fun awaitSessionWaitingOnALock(url: String) {
        DriverManager.getConnection(url).use { connection ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (System.nanoTime() < deadline) {
                connection.createStatement().use { statement ->
                    statement.executeQuery(BLOCKED_SESSIONS).use { rows ->
                        rows.next()
                        if (rows.getInt(1) > 0) return
                    }
                }
                Thread.sleep(POLL_MILLIS)
            }
        }
        fail("the second transaction never waited on the first, so the race did not happen")
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
        const val BLOCKED_SESSIONS = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL"
    }
}

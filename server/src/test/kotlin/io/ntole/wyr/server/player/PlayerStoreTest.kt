package io.ntole.wyr.server.player

import io.ntole.wyr.server.db.Players
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * Holds the first award's transaction open while the second one starts, so the second cannot
     * see the first's point until it commits. A read-then-write would start from the stale total
     * and overwrite the first point with its own.
     */
    private fun raceTwoAwards(
        databaseName: String,
        isolationLevel: Int,
    ) {
        val url = "jdbc:h2:mem:$databaseName;DB_CLOSE_DELAY=-1"
        val database =
            Database.connect(
                url = url,
                driver = "org.h2.Driver",
                databaseConfig = DatabaseConfig { defaultIsolationLevel = isolationLevel },
            )
        val player =
            transaction(database) {
                SchemaUtils.create(Players)
                PlayerStore.createGuest(refreshTokenHash = databaseName, refreshExpiresAt = Long.MAX_VALUE)
            }

        val firstHasAwarded = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first =
                pool.submit(
                    Callable {
                        transaction(database) {
                            PlayerStore.addPoints(player.id, points = 1).also {
                                firstHasAwarded.countDown()
                                releaseFirst.await()
                            }
                        }
                    },
                )
            assertTrue(firstHasAwarded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the first award never ran")

            val second =
                pool.submit(
                    Callable { transaction(database) { PlayerStore.addPoints(player.id, points = 1) } },
                )
            awaitSessionWaitingOnALock(url)
            releaseFirst.countDown()

            assertEquals(1, first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(2, second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the second award must build on the first")
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
        }

        assertEquals(2, transaction(database) { PlayerStore.find(player.id)?.totalPoints })
    }

    /**
     * Returns once some session is queued behind another's row lock: the second award waiting on
     * the first. Releasing the first any sooner could let the second start after the first had
     * committed, and then the race this test is for would never happen.
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
        fail("the second award never waited on the first, so the race did not happen")
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val POLL_MILLIS = 5L
        const val BLOCKED_SESSIONS = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL"
    }
}

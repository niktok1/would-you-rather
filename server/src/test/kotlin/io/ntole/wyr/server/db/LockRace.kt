package io.ntole.wyr.server.db

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * Deterministic races for the store tests, which pin what READ COMMITTED lets through.
 *
 * H2 only: the wait for a lock is observed through H2's INFORMATION_SCHEMA.SESSIONS, so these run
 * on H2 even in the server-postgres CI job. Porting them means polling pg_stat_activity instead.
 */

internal fun h2Url(databaseName: String) = "jdbc:h2:mem:$databaseName;DB_CLOSE_DELAY=-1"

internal fun connectH2(
    url: String,
    isolationLevel: Int,
): Database =
    Database.connect(
        url = url,
        driver = "org.h2.Driver",
        databaseConfig = DatabaseConfig { defaultIsolationLevel = isolationLevel },
    )

/**
 * Runs each of [blocks] in a transaction of its own, with the first held open until every
 * other one is queued behind its row lock, and returns their results in order.
 *
 * Each block runs through Exposed's `transaction`, retries included, exactly as `Db.query` runs a
 * request's work.
 */
internal fun <T> raceBehindFirst(
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

private const val TIMEOUT_SECONDS = 10L
private const val POLL_MILLIS = 5L
private const val BLOCKED_SESSIONS = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL"

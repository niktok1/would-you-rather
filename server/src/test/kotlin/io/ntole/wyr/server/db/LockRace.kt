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
 * other one is queued behind it, and returns their results in order.
 *
 * [queued] is the `INFORMATION_SCHEMA.SESSIONS` condition that picks out a session waiting on the
 * first. H2 names the blocker of a session waiting on a row lock, but not of one inserting a key
 * the first holds uncommitted ([INSERTING_INTO_VOTES]); that one shows only by what it executes.
 *
 * [whileQueued] runs once they all are, before the first is let go, so whatever it commits lands
 * after the first's work and before the rest of theirs.
 *
 * Each block runs through Exposed's `transaction`, retries included, exactly as `Db.query` runs a
 * request's work.
 */
internal fun <T> raceBehindFirst(
    url: String,
    database: Database,
    vararg blocks: () -> T,
    queued: String = WAITING_ON_A_ROW_LOCK,
    whileQueued: () -> Unit = {},
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
        awaitQueuedSessions(url, count = rest.size, queued)
        whileQueued()
        releaseFirst.countDown()

        return (listOf(first) + rest).map { result -> result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    } finally {
        releaseFirst.countDown()
        pool.shutdownNow()
    }
}

/**
 * Returns once [count] sessions match [queued]: every later transaction waiting on the first.
 * Releasing the first any sooner could let one start after the first had committed, and then the
 * race the test is for would never happen.
 */
private fun awaitQueuedSessions(
    url: String,
    count: Int,
    queued: String,
) {
    DriverManager.getConnection(url).use { connection ->
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE $queued").use { rows ->
                    rows.next()
                    if (rows.getInt(1) >= count) return
                }
            }
            Thread.sleep(POLL_MILLIS)
        }
    }
    fail("fewer than $count transactions waited on the first, so the race did not happen")
}

/** A session waiting for a row lock another holds. */
internal const val WAITING_ON_A_ROW_LOCK = "BLOCKER_ID IS NOT NULL"

/**
 * A session inside an insert into `votes`. An insert of a key another transaction holds uncommitted
 * waits for that transaction to end, so once the first has inserted, one seen here is queued on it.
 */
internal const val INSERTING_INTO_VOTES = "UPPER(EXECUTING_STATEMENT) LIKE 'INSERT INTO VOTES%'"

private const val TIMEOUT_SECONDS = 10L
private const val POLL_MILLIS = 5L

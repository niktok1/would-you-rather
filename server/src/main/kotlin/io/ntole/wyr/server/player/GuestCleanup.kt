package io.ntole.wyr.server.player

import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.db.Identities
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Deleting the guests nobody can reach any more (CLAUDE.md §8b, *Guest clean-up*): a player with no
 * username and no Play Games link, no session of whom could still refresh, idle past the retention.
 *
 * Nothing records when a player last played, and nothing need: every refresh sets its session's
 * refresh-token expiry a refresh token's lifetime ahead, and a playing client refreshes every 15
 * minutes, so a session's last refresh is its expiry less that lifetime. A guest is idle since the
 * latest of those, or since it was minted when no session of theirs is left (a logout deletes its
 * session). Such a guest can never be played again: without a username or a Play Games link nothing
 * signs in as them, and with no refresh token left alive no device holds a way back.
 */
object GuestCleanup {
    /**
     * Up to [limit] guests unreachable at [now], oldest first: minted at least [retentionMillis] ago,
     * and every session of theirs expired, its last refresh ([refreshTtlMillis] before its expiry) at
     * least [retentionMillis] ago too. Must run inside a transaction.
     */
    fun unreachable(
        now: Long,
        retentionMillis: Long,
        refreshTtlMillis: Long,
        limit: Int,
    ): List<String> =
        Players
            .select(Players.id)
            .where { unreachableAt(now, retentionMillis, refreshTtlMillis) }
            .orderBy(Players.createdAt to SortOrder.ASC, Players.id to SortOrder.ASC)
            .limit(limit)
            .map { row -> row[Players.id] }

    /**
     * Deletes [playerId] through [AccountDeletion.delete], so every author's points and every table stay
     * as a deletion of the account leaves them, if they are still unreachable once their row is locked,
     * and returns whether they were. Must run inside a transaction, one of its own.
     *
     * The row lock comes first, and the check after it, so of two instances cleaning up at once the
     * second waits and then finds the player gone, and a registration or a Play Games link that got
     * there first keeps them (CLAUDE.md §4): each writes the player's row, or a row whose foreign key
     * waits on it, and a refresh cannot bring back a token that has expired.
     */
    fun deleteIfUnreachable(
        playerId: String,
        now: Long,
        retentionMillis: Long,
        refreshTtlMillis: Long,
    ): Boolean {
        Players
            .select(Players.id)
            .where { Players.id eq playerId }
            .forUpdate()
            .singleOrNull()
            ?: return false
        val still =
            Players
                .select(Players.id)
                .where { (Players.id eq playerId) and unreachableAt(now, retentionMillis, refreshTtlMillis) }
                .any()
        if (!still) return false
        AccountDeletion.delete(playerId)
        return true
    }

    private fun unreachableAt(
        now: Long,
        retentionMillis: Long,
        refreshTtlMillis: Long,
    ): Op<Boolean> {
        val idleSince = now - retentionMillis
        // A session is still someone's way back while its token can refresh, or while its last refresh
        // is within the retention, whichever reaches further.
        val sessionLiveUntil = minOf(now, idleSince + refreshTtlMillis)
        val linked = Identities.select(Identities.playerId).where { Identities.playerId eq Players.id }
        val liveSession =
            Sessions
                .select(Sessions.id)
                .where {
                    (Sessions.playerId eq Players.id) and (Sessions.refreshTokenExpiresAt greater sessionLiveUntil)
                }
        return Players.username.isNull() and
            (Players.createdAt lessEq idleSince) and
            notExists(linked) and
            notExists(liveSession)
    }
}

/**
 * Runs [GuestCleanup] at boot and then every [interval], in [scope], the server's background scope,
 * which stops with the server. Best effort: a run that fails is logged, by the failure's kind alone, and
 * the next run tries again. Each run logs one INFO line with how many it deleted, never who.
 */
class GuestCleanupJob(
    private val db: Db,
    private val retention: Duration,
    private val refreshTtl: Duration,
    private val scope: CoroutineScope,
    private val interval: Duration = INTERVAL,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Starts the runs, the first at once, and returns the job running them. */
    fun start(): Job =
        scope.launch {
            while (isActive) {
                runOnce()
                delay(interval)
            }
        }

    /**
     * Deletes every guest unreachable now, [BATCH_SIZE] read at a time and each deleted in a transaction
     * of its own, at most [MAX_BATCHES] batches a run, and returns how many it deleted. Never throws but
     * for cancellation.
     */
    suspend fun runOnce(): Int {
        val at = now()
        val retentionMillis = retention.inWholeMilliseconds
        val refreshTtlMillis = refreshTtl.inWholeMilliseconds
        var deleted = 0
        try {
            var batches = 0
            do {
                val batch = db.query { GuestCleanup.unreachable(at, retentionMillis, refreshTtlMillis, BATCH_SIZE) }
                val deletedNow =
                    batch.count { id ->
                        db.query { GuestCleanup.deleteIfUnreachable(id, at, retentionMillis, refreshTtlMillis) }
                    }
                deleted += deletedNow
                batches++
                // A short batch was the last; one another instance emptied first leaves the rest to it.
            } while (batch.size == BATCH_SIZE && deletedNow > 0 && batches < MAX_BATCHES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            log.warn(
                "guest clean-up failed after deleting $deleted guests: ${failed::class.java.simpleName}; " +
                    "the next run tries again",
            )
            return deleted
        }
        log.info("guest clean-up deleted $deleted guests idle ${retention.inWholeDays} days or more")
        return deleted
    }

    companion object {
        /** How often it runs after the boot's run. */
        val INTERVAL: Duration = 24.hours

        /** How many guests one read finds. */
        const val BATCH_SIZE: Int = 100

        /** How many batches one run reads at most, so a run is bounded; the next run goes on. */
        const val MAX_BATCHES: Int = 100

        private val log = LoggerFactory.getLogger(GuestCleanupJob::class.java)
    }
}

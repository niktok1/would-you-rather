package io.ntole.wyr.server.player

import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.server.auth.AccountStore
import io.ntole.wyr.server.auth.IdentityProvider
import io.ntole.wyr.server.auth.IdentityStore
import io.ntole.wyr.server.auth.SessionStore
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.printed
import io.ntole.wyr.server.reaction.ReactionStore
import io.ntole.wyr.server.vote.Scoring
import io.ntole.wyr.server.vote.VoteStore
import io.ntole.wyr.server.withLogCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The guest clean-up (CLAUDE.md §8b, *Guest clean-up*) through the stores at READ COMMITTED, on the
 * first seeds, with the clock fixed: who is deleted, who is kept, and that every other author's points
 * still add up.
 */
class GuestCleanupTest {
    private val url = h2Url("wyr-guest-cleanup-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)
    private val db = Db(database)
    private val now = System.currentTimeMillis()

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.writeMissing(TEST_SEEDS)
        }
    }

    @Test
    fun `guests idle past the retention are deleted and everyone who can still play is kept`() {
        val idleNoSession = guest(mintedDaysAgo = 91)
        val idleExpiredSession = guest(mintedDaysAgo = 200).also { session(it, expiresDaysAgo = 61) }
        val fresh = guest(mintedDaysAgo = 0).also { session(it, expiresDaysAgo = -30) }
        val justInside = guest(mintedDaysAgo = 89)
        val refreshedLately = guest(mintedDaysAgo = 200).also { session(it, expiresDaysAgo = 10) }
        val liveSession = guest(mintedDaysAgo = 200).also { session(it, expiresDaysAgo = -1) }
        val oneLiveOfTwo =
            guest(mintedDaysAgo = 200).also {
                session(it, expiresDaysAgo = 100)
                session(it, expiresDaysAgo = -5)
            }
        val registered = guest(mintedDaysAgo = 200).also { register(it) }
        val linked = guest(mintedDaysAgo = 200).also { linkPlayGames(it) }

        val deleted = runBlocking { job().runOnce() }

        assertEquals(2, deleted)
        assertFalse(exists(idleNoSession), "idle 91 days, and nothing left to refresh")
        assertFalse(exists(idleExpiredSession), "its last refresh 91 days ago")
        listOf(fresh, justInside, refreshedLately, liveSession, oneLiveOfTwo, registered, linked).forEach { kept ->
            assertTrue(exists(kept), "$kept is kept")
        }
    }

    @Test
    fun `a second run deletes nothing more and changes nothing`() {
        guest(mintedDaysAgo = 120)

        assertEquals(1, runBlocking { job().runOnce() })
        assertEquals(0, runBlocking { job().runOnce() })
    }

    @Test
    fun `a deleted guest's likes are taken back and every other author's sum holds`() {
        val author = guest(mintedDaysAgo = 0).also { register(it) }
        val liker = guest(mintedDaysAgo = 0)
        val idle = guest(mintedDaysAgo = 100)
        val (first, second) = question(author) to question(author)
        transaction(database) {
            ReactionStore.set(idle, first, Reaction.LIKE)
            ReactionStore.set(idle, second, Reaction.LIKE)
            ReactionStore.set(liker, first, Reaction.LIKE)
            VoteStore.cast(author, "seed-1", OptionSide.A, attemptId = "a1")
            VoteStore.cast(idle, first, OptionSide.B, attemptId = "b1")
        }
        val before = statsOf(author).totalPoints

        assertEquals(1, runBlocking { job().runOnce() })

        val stats = statsOf(author)
        assertEquals(before - 2 * Scoring.POINTS_PER_LIKE, stats.totalPoints, "the idle guest's two likes taken back")
        assertEquals(1, stats.likesReceived, "the liker's stays")
        assertEquals(
            stats.answersGiven * Scoring.POINTS_PER_ANSWER + stats.likesReceived * Scoring.POINTS_PER_LIKE -
                stats.pointsSpent,
            stats.totalPoints,
        )
    }

    @Test
    fun `two instances cleaning up at once delete each guest once and fail nothing`() {
        val idle = List(GuestCleanupJob.BATCH_SIZE + 20) { guest(mintedDaysAgo = 100) }
        val kept = guest(mintedDaysAgo = 0)

        val counts =
            runBlocking(Dispatchers.IO) {
                List(2) { async { job().runOnce() } }.awaitAll()
            }

        assertEquals(idle.size, counts.sum(), "each deleted by one of the two")
        assertTrue(idle.none(::exists))
        assertTrue(exists(kept))
    }

    @Test
    fun `a run logs one line with the count and no id`() {
        val idle = List(3) { guest(mintedDaysAgo = 100) }

        withLogCapture { logged ->
            runBlocking { job().runOnce() }

            val lines = logged.printed().filter { it.contains("guest clean-up") }
            assertEquals(1, lines.size, "$lines")
            assertTrue(lines.single().startsWith("guest clean-up deleted 3 guests idle 90 days"), lines.single())
            idle.forEach { id -> assertFalse(logged.printed().any { it.contains(id) }, "no id logged") }
        }
    }

    @Test
    fun `the job runs at once when started and stops with its scope`() {
        val idle = guest(mintedDaysAgo = 100)
        val scope = CoroutineScope(Dispatchers.IO)
        val running = GuestCleanupJob(db, 90.days, REFRESH_TTL, scope, interval = 1.hours).start()

        runBlocking {
            withTimeout(10.seconds) {
                while (exists(idle)) delay(10)
            }
            running.cancelAndJoin()
        }

        assertTrue(running.isCancelled)
    }

    private fun job() = GuestCleanupJob(db, 90.days, REFRESH_TTL, CoroutineScope(Dispatchers.IO), now = { now })

    private fun guest(mintedDaysAgo: Int): String =
        transaction(database) {
            val id = PlayerStore.createGuest().id
            Players.update(
                { Players.id eq id },
            ) { row -> row[createdAt] = now - mintedDaysAgo.days.inWholeMilliseconds }
            id
        }

    /** A session of [player]'s whose refresh token expires [expiresDaysAgo] days ago, or ahead when negative. */
    private fun session(
        player: String,
        expiresDaysAgo: Int,
    ) = transaction(database) {
        SessionStore.open(
            player,
            refreshTokenHash = "h-${UUID.randomUUID()}",
            expiresAt = now - expiresDaysAgo.days.inWholeMilliseconds,
        )
    }

    private fun register(player: String) =
        transaction(database) { AccountStore.register(player, "u${player.take(8)}", "not-a-password-hash") }

    private fun linkPlayGames(player: String) =
        transaction(database) { IdentityStore.signIn(IdentityProvider.PLAY_GAMES, "pg-$player", player) }

    private fun question(author: String): String {
        val id = UUID.randomUUID().toString()
        transaction(database) {
            Questions.insert { row ->
                row[Questions.id] = id
                row[optionA] = "A of $id"
                row[optionB] = "B of $id"
                row[authorPlayerId] = author
                row[status] = QuestionStatus.APPROVED
                row[submittedAt] = now
                row[reviewedAt] = now
            }
            QuestionCategories.insert { row ->
                row[questionId] = id
                row[category] = "FOOD"
            }
        }
        return id
    }

    private fun exists(player: String): Boolean =
        transaction(database) { Players.select(Players.id).where { Players.id eq player }.any() }

    private fun statsOf(player: String) = checkNotNull(transaction(database) { StatsStore.of(player) })

    private companion object {
        val REFRESH_TTL = 30.days
    }
}

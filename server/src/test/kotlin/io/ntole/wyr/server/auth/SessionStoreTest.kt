package io.ntole.wyr.server.auth

import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.Sessions
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.PlayerStore
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * A player's sessions (CLAUDE.md §8a, *Sessions*): the refresh rules, per session. Every race runs at
 * READ COMMITTED by hand, not taken from the server, so it keeps telling a compare-and-set from a
 * read-then-write whatever the server's level becomes: only there does a rotation by id alone let two
 * racers through.
 */
class SessionStoreTest {
    @Test
    fun `with the grace off, two refreshes racing with the same token let exactly one through`() {
        // Under REPEATABLE_READ the second is refused, and its retry no longer finds the token.
        val url = h2Url("wyr-session-store-refresh")
        val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "spent") }

        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { rotate("spent", newHash = "first", graceMillis = 0) },
                { rotate("spent", newHash = "second", graceMillis = 0) },
            )

        assertEquals(player.id, first)
        assertNull(second, "the token was already spent by the first refresh")
        assertEquals("first", storedTokens(database, player.id).current, "the first refresh's session must stay live")
    }

    /**
     * The grace window's point (CLAUDE.md §8a). Two refreshes present the current token at once, as a
     * refresh whose answer was lost and its retry can, or two clients sharing one store. The second
     * waits on the first's row lock, then finds the token it presented displaced, and spends it as the
     * previous one: both go through, and the first's new token, which the second displaced, is the
     * previous one in its turn, still good until the next rotation. The same with no time bound and
     * under one, which adds the stamp to the `WHERE` the second re-checks.
     */
    @Test
    fun `two refreshes racing with the current token both go through, the first's token becoming the previous one`() {
        listOf(null, BOUND_MILLIS).forEach { graceMillis ->
            val url = h2Url("wyr-session-store-refresh-grace-${graceMillis ?: "unbounded"}")
            val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)
            val player = transaction(database) { createPlayer(refreshTokenHash = "shared") }

            val (first, second) =
                raceBehindFirst(
                    url,
                    database,
                    { rotate("shared", newHash = "first", now = ROTATED_AT, graceMillis = graceMillis) },
                    { rotate("shared", newHash = "second", now = ROTATED_AT + 1, graceMillis = graceMillis) },
                )

            assertEquals(player.id, first, "bound $graceMillis")
            assertEquals(player.id, second, "the second spends the token as the previous one: bound $graceMillis")
            assertEquals(StoredTokens("second", "first", ROTATED_AT + 1), storedTokens(database, player.id))
            val third =
                transaction(database) {
                    rotate("first", newHash = "third", now = ROTATED_AT + 2, graceMillis = graceMillis)
                }
            assertEquals(player.id, third, "bound $graceMillis")
        }
    }

    /**
     * The displaced token works once, however long it waits as the previous one: of two refreshes
     * presenting it at once, the second waits on the first's row lock and then finds it displaced for
     * good. By id, or by the hash alone, both would go through from one state, and it could be spent
     * again and again. The same with no time bound and under one.
     */
    @Test
    fun `two refreshes racing with the previous token let exactly one through`() {
        listOf(null, BOUND_MILLIS).forEach { graceMillis ->
            val url = h2Url("wyr-session-store-refresh-previous-${graceMillis ?: "unbounded"}")
            val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)
            val player = transaction(database) { createPlayer(refreshTokenHash = "displaced") }
            transaction(database) {
                rotate("displaced", newHash = "current", now = ROTATED_AT, graceMillis = graceMillis)
            }

            val (first, second) =
                raceBehindFirst(
                    url,
                    database,
                    { rotate("displaced", newHash = "first", now = ROTATED_AT + 1, graceMillis = graceMillis) },
                    { rotate("displaced", newHash = "second", now = ROTATED_AT + 1, graceMillis = graceMillis) },
                )

            assertEquals(player.id, first, "bound $graceMillis")
            assertNull(second, "the previous token was spent by the first refresh: bound $graceMillis")
            assertEquals(StoredTokens("first", "current", ROTATED_AT + 1), storedTokens(database, player.id))
        }
    }

    /**
     * The lost answer the grace is for (CLAUDE.md §8a), sent again days later, as a player back after
     * that long sends it: with no time bound, the token the rotation displaced still keeps them. Spent,
     * it is displaced for good, however long after.
     */
    @Test
    fun `with no time bound, a token a rotation displaced still rotates days later, and then never again`() {
        val database = connectH2(h2Url("wyr-session-store-unbounded"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "lost") }
        transaction(database) { rotate("lost", newHash = "never-received", now = ROTATED_AT) }
        val daysLater = ROTATED_AT + 3.days.inWholeMilliseconds

        val retried = transaction(database) { rotate("lost", newHash = "retry", now = daysLater) }
        val replayed = transaction(database) { rotate("lost", newHash = "replayed", now = daysLater + 1) }

        assertEquals(player.id, retried, "a retry days later keeps the player")
        assertNull(replayed, "a token works twice at most: once current, once more as the previous one")
        assertEquals(StoredTokens("retry", "never-received", daysLater), storedTokens(database, player.id))
    }

    /**
     * What ends a displaced token with no time bound: the next rotation, here the first use of the token
     * that displaced it, which makes that one the previous token in its place.
     */
    @Test
    fun `a token a rotation displaced dies at the first use of the token that displaced it`() {
        val database = connectH2(h2Url("wyr-session-store-displaced-twice"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "original") }
        transaction(database) { rotate("original", newHash = "first", now = ROTATED_AT) }
        transaction(database) { rotate("first", newHash = "second", now = ROTATED_AT + 1) }

        val rotated = transaction(database) { rotate("original", newHash = "late", now = ROTATED_AT + 2) }

        assertNull(rotated, "displaced for good by the rotation after the one that displaced it")
        assertEquals(StoredTokens("second", "first", ROTATED_AT + 1), storedTokens(database, player.id))
    }

    @Test
    fun `under a time bound, a token a rotation displaced still rotates within it, and not a moment after`() {
        val database = connectH2(h2Url("wyr-session-store-grace-ends"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "lost") }
        transaction(database) { rotateUnderBound("lost", newHash = "never-received", now = ROTATED_AT) }

        val late = transaction(database) { rotateUnderBound("lost", newHash = "late", now = ROTATED_AT + BOUND_MILLIS) }
        val inTime =
            transaction(database) { rotateUnderBound("lost", newHash = "retry", now = ROTATED_AT + BOUND_MILLIS - 1) }

        assertNull(late, "the bound has passed")
        assertEquals(player.id, inTime, "a retry within the bound keeps the player")
        assertEquals(
            StoredTokens("retry", "never-received", ROTATED_AT + BOUND_MILLIS - 1),
            storedTokens(database, player.id),
        )
    }

    @Test
    fun `under a time bound, a token spent as the previous one is dead, and the one it displaced gets its own`() {
        val database = connectH2(h2Url("wyr-session-store-spent-twice"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "original") }
        val secondRotation = ROTATED_AT + BOUND_MILLIS - 1
        val lastMomentOfItsBound = secondRotation + BOUND_MILLIS - 1
        transaction(database) { rotateUnderBound("original", newHash = "first", now = ROTATED_AT) }
        val spentAsPrevious =
            transaction(database) { rotateUnderBound("original", newHash = "second", now = secondRotation) }
        assertEquals(player.id, spentAsPrevious, "spent once more, within the bound")

        val replayed =
            transaction(database) { rotateUnderBound("original", newHash = "replayed", now = secondRotation) }
        val displaced =
            transaction(database) { rotateUnderBound("first", newHash = "third", now = lastMomentOfItsBound) }

        assertNull(replayed, "a token works twice at most: once current, once more as the previous one")
        assertEquals(player.id, displaced, "its bound runs from the rotation that displaced it, not the first")
    }

    @Test
    fun `with the grace off, a token a rotation displaced is dead at once`() {
        val database = connectH2(h2Url("wyr-session-store-no-grace"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "old") }
        transaction(database) { rotate("old", newHash = "new", now = ROTATED_AT, graceMillis = 0) }

        val rotated = transaction(database) { rotate("old", newHash = "again", now = ROTATED_AT, graceMillis = 0) }

        assertNull(rotated)
        assertEquals(StoredTokens("new", "old", ROTATED_AT), storedTokens(database, player.id))
    }

    @Test
    fun `an expired refresh token does not rotate`() {
        val database = connectH2(h2Url("wyr-session-store-expired"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "stale", refreshExpiresAt = 1_000L) }

        val rotated = transaction(database) { rotate("stale", newHash = "fresh", now = 1_000L) }

        assertNull(rotated)
        assertEquals("stale", storedTokens(database, player.id).current)
    }

    /**
     * A displaced token keeps the expiry it had while current: the grace lets it outlive its rotation,
     * never its own lifetime, which is all that bounds it when nothing else does.
     */
    @Test
    fun `the grace never outlasts a displaced token's own expiry, bounded in time or not`() {
        listOf(null, BOUND_MILLIS).forEach { graceMillis ->
            val url = h2Url("wyr-session-store-grace-expiry-${graceMillis ?: "unbounded"}")
            val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)
            val expiry = ROTATED_AT + BOUND_MILLIS / 2
            val player = transaction(database) { createPlayer(refreshTokenHash = "ending", refreshExpiresAt = expiry) }
            transaction(database) { rotate("ending", newHash = "current", now = ROTATED_AT, graceMillis = graceMillis) }

            val expired =
                transaction(database) { rotate("ending", newHash = "late", now = expiry, graceMillis = graceMillis) }
            val unexpired =
                transaction(database) {
                    rotate("ending", newHash = "retry", now = expiry - 1, graceMillis = graceMillis)
                }

            assertNull(expired, "within any bound, but past its own expiry: bound $graceMillis")
            assertEquals(player.id, unexpired, "bound $graceMillis")
        }
    }

    /**
     * The point of sessions (CLAUDE.md §8a): each device's tokens are its own, so however often one
     * refreshes, another's token is untouched and still spends as current.
     */
    @Test
    fun `a player's sessions rotate apart, so refreshing on one device never spends another's token`() {
        val database = connectH2(h2Url("wyr-session-store-devices"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "phone") }
        transaction(database) { SessionStore.open(player.id, "tablet", Long.MAX_VALUE, now = ROTATED_AT) }
        listOf("tablet", "tablet-1", "tablet-2").forEachIndexed { index, presented ->
            val rotated =
                transaction(database) { rotate(presented, newHash = "tablet-${index + 1}", now = ROTATED_AT + index) }
            assertEquals(player.id, rotated, "the tablet's refresh ${index + 1}")
        }

        val phone = transaction(database) { rotate("phone", newHash = "phone-1", now = ROTATED_AT + 3) }

        assertEquals(player.id, phone, "the phone's token, current all along")
        assertEquals(
            setOf(
                StoredTokens("phone-1", "phone", ROTATED_AT + 3),
                StoredTokens("tablet-3", "tablet-2", ROTATED_AT + 2),
            ),
            sessionsOf(database, player.id),
        )
    }

    /** A rotation answers the session the token belongs to, which the new access token then names. */
    @Test
    fun `a rotation answers the session the presented token belongs to`() {
        val database = connectH2(h2Url("wyr-session-store-answer"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "phone") }
        val tablet = transaction(database) { SessionStore.open(player.id, "tablet", Long.MAX_VALUE) }

        val rotated =
            transaction(database) { SessionStore.rotate("tablet", "tablet-1", Long.MAX_VALUE, graceMillis = null) }

        assertEquals(tablet, rotated)
    }

    /**
     * A logout (CLAUDE.md §8a, *Sessions*): the session's current token and the previous one the grace
     * keeps both die, and the player's session on another device refreshes as before.
     */
    @Test
    fun `closing a session ends both its tokens and leaves the player's other sessions alone`() {
        val database = connectH2(h2Url("wyr-session-store-close"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "tablet") }
        val phone = transaction(database) { SessionStore.open(player.id, "phone", Long.MAX_VALUE) }
        transaction(database) { rotate("phone", newHash = "phone-1", now = ROTATED_AT) }

        transaction(database) { SessionStore.close(phone.id, player.id) }

        assertNull(transaction(database) { rotate("phone-1", newHash = "phone-2") }, "the current token")
        assertNull(transaction(database) { rotate("phone", newHash = "phone-3") }, "the previous token")
        assertEquals(player.id, transaction(database) { rotate("tablet", newHash = "tablet-1") }, "the other device")
    }

    @Test
    fun `closing another player's session or one closed already changes nothing`() {
        val database = connectH2(h2Url("wyr-session-store-close-other"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "own") }
        val other = transaction(database) { PlayerStore.createGuest() }
        val session = transaction(database) { SessionStore.open(player.id, "kept", Long.MAX_VALUE) }

        transaction(database) { SessionStore.close(session.id, other.id) }
        transaction(database) { SessionStore.close("no-such-session", player.id) }

        assertEquals(player.id, transaction(database) { rotate("kept", newHash = "kept-1") }, "another player's close")
        transaction(database) { SessionStore.close(session.id, player.id) }
        transaction(database) { SessionStore.close(session.id, player.id) }
        assertEquals(player.id, transaction(database) { rotate("own", newHash = "own-1") }, "a close twice")
    }

    /**
     * The players row's old refresh-token columns are unused (CLAUDE.md §8a, *Sessions*): neither a mint
     * nor an opened session nor a rotation writes them, and a rotation touches no players row at all.
     */
    @Test
    fun `neither a mint nor a session nor a rotation writes the players row's old refresh-token columns`() {
        val database = connectH2(h2Url("wyr-session-store-unused-columns"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "phone") }
        transaction(database) { SessionStore.open(player.id, "tablet", Long.MAX_VALUE, now = ROTATED_AT) }
        transaction(database) { rotate("phone", newHash = "phone-1", now = ROTATED_AT + 1) }

        assertEquals(UnusedColumns(null, null, null, null), unusedColumnsOf(database, player.id))
    }

    /**
     * [SessionStore.rotate], with a lifetime no test reaches, and with no time bound unless a test sets
     * one.
     */
    private fun rotate(
        presentedHash: String,
        newHash: String,
        now: Long = System.currentTimeMillis(),
        graceMillis: Long? = null,
    ): String? =
        SessionStore
            .rotate(
                presentedHash = presentedHash,
                newHash = newHash,
                expiresAt = Long.MAX_VALUE,
                graceMillis = graceMillis,
                now = now,
            )?.playerId

    /** [rotate] under the time bound [BOUND_MILLIS]. */
    private fun rotateUnderBound(
        presentedHash: String,
        newHash: String,
        now: Long,
    ): String? = rotate(presentedHash, newHash, now, graceMillis = BOUND_MILLIS)

    /**
     * Creates the players and sessions tables and one player in them, with a session holding
     * [refreshTokenHash], as a mint leaves them. Must run inside a transaction.
     */
    private fun createPlayer(
        refreshTokenHash: String = "unused",
        refreshExpiresAt: Long = Long.MAX_VALUE,
    ): PlayerStore.Player {
        SchemaUtils.create(Players, Sessions)
        return PlayerStore.createGuest().also { player ->
            SessionStore.open(player.id, refreshTokenHash, refreshExpiresAt)
        }
    }

    /** The hashes a session holds, and when the previous one was displaced. */
    private data class StoredTokens(
        val current: String,
        val previous: String?,
        val previousRotatedAt: Long?,
    )

    /** What a player's row holds in the refresh-token columns nothing uses any more. */
    private data class UnusedColumns(
        val current: String?,
        val previous: String?,
        val previousRotatedAt: Long?,
        val marked: String?,
    )

    /** What the one session of [playerId] holds. */
    private fun storedTokens(
        database: Database,
        playerId: String,
    ): StoredTokens = sessionsOf(database, playerId).single()

    private fun sessionsOf(
        database: Database,
        playerId: String,
    ): Set<StoredTokens> =
        transaction(database) {
            Sessions
                .selectAll()
                .where { Sessions.playerId eq playerId }
                .map { row ->
                    StoredTokens(
                        current = row[Sessions.refreshTokenHash],
                        previous = row[Sessions.previousRefreshTokenHash],
                        previousRotatedAt = row[Sessions.previousRefreshTokenRotatedAt],
                    )
                }.toSet()
                .also { sessions -> assertTrue(sessions.isNotEmpty(), "player $playerId has no session") }
        }

    private fun unusedColumnsOf(
        database: Database,
        playerId: String,
    ): UnusedColumns =
        transaction(database) {
            Players
                .selectAll()
                .where { Players.id eq playerId }
                .single()
                .let { row ->
                    UnusedColumns(
                        current = row[Players.refreshTokenHash],
                        previous = row[Players.previousRefreshTokenHash],
                        previousRotatedAt = row[Players.previousRefreshTokenRotatedAt],
                        marked = row[Players.mirroredRefreshTokenHash],
                    )
                }
        }

    private companion object {
        /** The time bound the tests of one set: 10 minutes, as the grace had by default at first. */
        val BOUND_MILLIS = 10.minutes.inWholeMilliseconds

        /** When the refresh tests' first rotation happens, far from 0 so a bound before it is no concern. */
        const val ROTATED_AT = 1_000_000_000L
    }
}

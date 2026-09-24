package io.ntole.wyr.server.player

import com.zaxxer.hikari.HikariDataSource
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.db.DatabaseFactory
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer() }

        val (first, second) =
            raceBehindFirst(
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
    fun `two answers counted at once for the same player both count`() {
        // READ COMMITTED by hand, for the same reason as the awards above.
        val url = h2Url("wyr-player-store-answers")
        val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer() }

        raceBehindFirst(url, database, { PlayerStore.countAnswer(player.id) }, { PlayerStore.countAnswer(player.id) })

        assertEquals(2, storedAnswersGiven(database, player.id), "the second answer must build on the first")
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

            val award = { PlayerStore.addPoints(player.id, points = 1) }
            val totals = raceBehindFirst(url, database, *Array(BURST) { award })

            assertEquals((1..BURST).toList(), totals.sorted(), "each award must build on every one before it")
            assertEquals(BURST, transaction(database) { PlayerStore.find(player.id)?.totalPoints })
        }
    }

    @Test
    fun `with the grace off, two refreshes racing with the same token let exactly one through`() {
        // READ COMMITTED by hand for the same reason as the awards: only here does a rotation by
        // id alone let both through. Under REPEATABLE_READ the second is refused, and its retry no
        // longer finds the token.
        val url = h2Url("wyr-player-store-refresh")
        val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "spent") }

        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { rotate("spent", newHash = "first", graceMillis = 0) },
                { rotate("spent", newHash = "second", graceMillis = 0) },
            )

        assertEquals(player.id, first?.id)
        assertNull(second, "the token was already spent by the first refresh")
        assertEquals("first", storedTokens(database, player.id).current, "the first refresh's session must stay live")
    }

    /**
     * The grace window's point (CLAUDE.md §8a). Two refreshes present the current token at once, as a
     * refresh whose answer was lost and its retry can, or two clients sharing one store. The second
     * waits on the first's row lock, then finds the token it presented displaced, and spends it as the
     * previous one: both go through, and the first's new token, which the second displaced, is the
     * previous one in its turn, still good within the grace.
     */
    @Test
    fun `two refreshes racing with the current token both go through, the first's token becoming the previous one`() {
        val url = h2Url("wyr-player-store-refresh-grace")
        val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "shared") }

        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { rotate("shared", newHash = "first", now = ROTATED_AT) },
                { rotate("shared", newHash = "second", now = ROTATED_AT + 1) },
            )

        assertEquals(player.id, first?.id)
        assertEquals(player.id, second?.id, "the second spends the token as the previous one")
        assertEquals(StoredTokens("second", "first", ROTATED_AT + 1), storedTokens(database, player.id))
        assertEquals(player.id, transaction(database) { rotate("first", newHash = "third", now = ROTATED_AT + 2) }?.id)
    }

    /**
     * The displaced token works once, not for as long as the grace lasts: of two refreshes presenting it
     * at once, the second waits on the first's row lock and then finds it displaced for good. By id, or
     * by the hash alone, both would go through from one state, and it could be spent again and again.
     */
    @Test
    fun `two refreshes racing with the previous token let exactly one through`() {
        val url = h2Url("wyr-player-store-refresh-previous")
        val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "displaced") }
        transaction(database) { rotate("displaced", newHash = "current", now = ROTATED_AT) }

        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { rotate("displaced", newHash = "first", now = ROTATED_AT + 1) },
                { rotate("displaced", newHash = "second", now = ROTATED_AT + 1) },
            )

        assertEquals(player.id, first?.id)
        assertNull(second, "the previous token was spent by the first refresh")
        assertEquals(StoredTokens("first", "current", ROTATED_AT + 1), storedTokens(database, player.id))
    }

    @Test
    fun `a token a rotation displaced still rotates within the grace, and not a moment after`() {
        val database = connectH2(h2Url("wyr-player-store-grace-ends"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "lost") }
        transaction(database) { rotate("lost", newHash = "never-received", now = ROTATED_AT) }

        val late = transaction(database) { rotate("lost", newHash = "late", now = ROTATED_AT + GRACE_MILLIS) }
        val inTime = transaction(database) { rotate("lost", newHash = "retry", now = ROTATED_AT + GRACE_MILLIS - 1) }

        assertNull(late, "the grace has passed")
        assertEquals(player.id, inTime?.id, "a retry within the grace keeps the player")
        assertEquals(
            StoredTokens("retry", "never-received", ROTATED_AT + GRACE_MILLIS - 1),
            storedTokens(database, player.id),
        )
    }

    @Test
    fun `a token spent as the previous one is dead, and the one it displaced gets a grace of its own`() {
        val database = connectH2(h2Url("wyr-player-store-spent-twice"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "original") }
        val secondRotation = ROTATED_AT + GRACE_MILLIS - 1
        val lastMomentOfItsGrace = secondRotation + GRACE_MILLIS - 1
        transaction(database) { rotate("original", newHash = "first", now = ROTATED_AT) }
        val spentAsPrevious = transaction(database) { rotate("original", newHash = "second", now = secondRotation) }
        assertEquals(player.id, spentAsPrevious?.id, "spent once more, within the grace")

        val replayed = transaction(database) { rotate("original", newHash = "replayed", now = secondRotation) }
        val displaced = transaction(database) { rotate("first", newHash = "third", now = lastMomentOfItsGrace) }

        assertNull(replayed, "a token works twice at most: once current, once more as the previous one")
        assertEquals(player.id, displaced?.id, "its grace runs from the rotation that displaced it, not the first")
    }

    @Test
    fun `with the grace off, a token a rotation displaced is dead at once`() {
        val database = connectH2(h2Url("wyr-player-store-no-grace"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "old") }
        transaction(database) { rotate("old", newHash = "new", now = ROTATED_AT, graceMillis = 0) }

        val rotated = transaction(database) { rotate("old", newHash = "again", now = ROTATED_AT, graceMillis = 0) }

        assertNull(rotated)
        assertEquals(StoredTokens("new", "old", ROTATED_AT), storedTokens(database, player.id))
    }

    @Test
    fun `an expired refresh token does not rotate`() {
        val database = connectH2(h2Url("wyr-player-store-expired"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "stale", refreshExpiresAt = 1_000L) }

        val rotated = transaction(database) { rotate("stale", newHash = "fresh", now = 1_000L) }

        assertNull(rotated)
        assertEquals("stale", storedTokens(database, player.id).current)
    }

    /**
     * A displaced token keeps the expiry it had while current: the grace lets it outlive its rotation,
     * never its own lifetime.
     */
    @Test
    fun `the grace never outlasts a displaced token's own expiry`() {
        val database = connectH2(h2Url("wyr-player-store-grace-expiry"), Connection.TRANSACTION_READ_COMMITTED)
        val expiry = ROTATED_AT + GRACE_MILLIS / 2
        val player = transaction(database) { createPlayer(refreshTokenHash = "ending", refreshExpiresAt = expiry) }
        transaction(database) { rotate("ending", newHash = "current", now = ROTATED_AT) }

        val expired = transaction(database) { rotate("ending", newHash = "late", now = expiry) }
        val unexpired = transaction(database) { rotate("ending", newHash = "retry", now = expiry - 1) }

        assertNull(expired, "within the grace, but past its own expiry")
        assertEquals(player.id, unexpired?.id)
    }

    /** [PlayerStore.rotateRefreshToken], with a lifetime no test reaches and the grace these tests assume. */
    private fun rotate(
        presentedHash: String,
        newHash: String,
        now: Long = System.currentTimeMillis(),
        graceMillis: Long = GRACE_MILLIS,
    ): PlayerStore.Player? =
        PlayerStore.rotateRefreshToken(
            presentedHash = presentedHash,
            newHash = newHash,
            expiresAt = Long.MAX_VALUE,
            graceMillis = graceMillis,
            now = now,
        )

    /** Creates the players table and one player in it. Must run inside a transaction. */
    private fun createPlayer(
        refreshTokenHash: String = "unused",
        refreshExpiresAt: Long = Long.MAX_VALUE,
    ): PlayerStore.Player {
        SchemaUtils.create(Players)
        return PlayerStore.createGuest(refreshTokenHash, refreshExpiresAt)
    }

    /** The hashes a player's row holds, and when the previous one was displaced. */
    private data class StoredTokens(
        val current: String?,
        val previous: String?,
        val previousRotatedAt: Long?,
    )

    private fun storedTokens(
        database: Database,
        playerId: String,
    ): StoredTokens =
        transaction(database) {
            Players
                .selectAll()
                .where { Players.id eq playerId }
                .single()
                .let { row ->
                    StoredTokens(
                        current = row[Players.refreshTokenHash],
                        previous = row[Players.previousRefreshTokenHash],
                        previousRotatedAt = row[Players.previousRefreshTokenRotatedAt],
                    )
                }
        }

    private fun storedAnswersGiven(
        database: Database,
        playerId: String,
    ): Int =
        transaction(database) {
            Players
                .select(Players.answersGiven)
                .where { Players.id eq playerId }
                .single()[Players.answersGiven]
        }

    private companion object {
        const val BURST = 8

        /** The grace the refresh tests run with: production's. */
        const val GRACE_MILLIS = ServerConfig.DEFAULT_REFRESH_GRACE_SECONDS * 1_000L

        /** When the refresh tests' first rotation happens, far from 0 so a grace before it is no concern. */
        const val ROTATED_AT = 1_000_000_000L
    }
}

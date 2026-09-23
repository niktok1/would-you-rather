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
    fun `two refreshes racing with the same token let exactly one through`() {
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
                { PlayerStore.rotateRefreshToken("spent", newHash = "first", expiresAt = Long.MAX_VALUE) },
                { PlayerStore.rotateRefreshToken("spent", newHash = "second", expiresAt = Long.MAX_VALUE) },
            )

        assertEquals(player.id, first?.id)
        assertNull(second, "the token was already spent by the first refresh")
        assertEquals("first", storedRefreshHash(database, player.id), "the first refresh's session must stay live")
    }

    @Test
    fun `an expired refresh token does not rotate`() {
        val database = connectH2(h2Url("wyr-player-store-expired"), Connection.TRANSACTION_READ_COMMITTED)
        val player = transaction(database) { createPlayer(refreshTokenHash = "stale", refreshExpiresAt = 1_000L) }

        val rotated =
            transaction(database) {
                PlayerStore.rotateRefreshToken("stale", newHash = "fresh", expiresAt = Long.MAX_VALUE, now = 1_000L)
            }

        assertNull(rotated)
        assertEquals("stale", storedRefreshHash(database, player.id))
    }

    /** Creates the players table and one player in it. Must run inside a transaction. */
    private fun createPlayer(
        refreshTokenHash: String = "unused",
        refreshExpiresAt: Long = Long.MAX_VALUE,
    ): PlayerStore.Player {
        SchemaUtils.create(Players)
        return PlayerStore.createGuest(refreshTokenHash, refreshExpiresAt)
    }

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
    }
}

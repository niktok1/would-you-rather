package io.ntole.wyr.server.player

import io.ntole.wyr.server.db.Players
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

object PlayerStore {
    data class Player(
        val id: String,
        val totalPoints: Int,
        /** Which pass over the questions the feed is on for this player (CLAUDE.md §8d). */
        val cycle: Int,
    )

    fun createGuest(
        refreshTokenHash: String,
        refreshExpiresAt: Long,
    ): Player {
        val id = UUID.randomUUID().toString()

        Players.insert { row ->
            row[Players.id] = id
            row[Players.createdAt] = System.currentTimeMillis()
            row[Players.totalPoints] = 0
            row[Players.refreshTokenHash] = refreshTokenHash
            row[Players.refreshTokenExpiresAt] = refreshExpiresAt
            row[Players.currentCycle] = Players.FIRST_CYCLE
        }

        return Player(id = id, totalPoints = 0, cycle = Players.FIRST_CYCLE)
    }

    fun find(id: String): Player? =
        Players
            .selectAll()
            .where { Players.id eq id }
            .limit(1)
            .firstOrNull()
            ?.let { row ->
                Player(
                    id = row[Players.id],
                    totalPoints = row[Players.totalPoints],
                    cycle = row[Players.currentCycle],
                )
            }

    /**
     * Swaps the refresh token whose hash is [oldHash] for [newHash] and returns its player. Must
     * run inside a transaction.
     *
     * Returns `null` alike for an unknown, an expired, and an already rotated token — the caller
     * must not be able to tell them apart, and neither should an attacker probing the endpoint.
     *
     * The write is a compare-and-set on [oldHash], not an update by id, so the token stays
     * single-use when two refreshes present it at once. At READ COMMITTED both find the player,
     * and the second then waits on the first's row lock and re-checks its `WHERE` against the row
     * the first committed. By id alone that still matches, and both succeed: two live sessions from
     * one token. With the old hash in it, nothing matches and the second is refused.
     */
    fun rotateRefreshToken(
        oldHash: String,
        newHash: String,
        expiresAt: Long,
        now: Long = System.currentTimeMillis(),
    ): Player? {
        val player = findByRefreshHash(oldHash, now) ?: return null

        val swapped =
            Players.update({
                (Players.id eq player.id) and
                    (Players.refreshTokenHash eq oldHash) and
                    (Players.refreshTokenExpiresAt greater now)
            }) { row ->
                row[refreshTokenHash] = newHash
                row[refreshTokenExpiresAt] = expiresAt
            }

        return player.takeIf { swapped == 1 }
    }

    private fun findByRefreshHash(
        hash: String,
        now: Long,
    ): Player? =
        Players
            .selectAll()
            .where { Players.refreshTokenHash eq hash }
            .limit(1)
            .firstOrNull()
            ?.takeIf { row -> (row[Players.refreshTokenExpiresAt] ?: 0L) > now }
            ?.let { row ->
                Player(
                    id = row[Players.id],
                    totalPoints = row[Players.totalPoints],
                    cycle = row[Players.currentCycle],
                )
            }

    /**
     * Starts the player's next cycle, provided they are still on cycle [from]. Must run inside a
     * transaction.
     *
     * A compare-and-set on [from], the cycle the caller read, not an increment by id: the feed
     * starts the next cycle when it finds nothing due, and two feed requests can find that at once.
     * At READ COMMITTED the second waits on the first's row lock and then re-checks its `WHERE`
     * against the row the first committed. By id alone that still matches, and the cycle moves
     * twice, cutting short the one the first had just started. With [from] in it, nothing matches
     * and the second leaves it alone, which is what it wanted anyway: the cycle after [from] exists.
     */
    fun startNextCycle(
        playerId: String,
        from: Int,
    ) {
        Players.update({ (Players.id eq playerId) and (Players.currentCycle eq from) }) { row ->
            row[currentCycle] = currentCycle + 1
        }
    }

    /**
     * Adds [points] to the player's total and returns the new total. Must run inside a transaction.
     *
     * The addition happens in SQL (`total_points = total_points + n`) rather than as a read and
     * then a write, so two votes by one player landing together cannot both start from the same
     * total and lose a point. At the server's READ COMMITTED (`DatabaseFactory`) that alone is
     * enough, however many awards land at once: each waits for the row lock of the one before,
     * then adds to the committed total, with no retry. A read-then-write at that level silently
     * drops the point.
     *
     * The total is read back in the same transaction, which holds the row lock from the update,
     * so it is exactly the total this award produced.
     */
    fun addPoints(
        playerId: String,
        points: Int,
    ): Int {
        val updated = Players.update({ Players.id eq playerId }) { row -> row[totalPoints] = totalPoints + points }
        check(updated == 1) { "player $playerId vanished mid-transaction" }

        return Players
            .select(Players.totalPoints)
            .where { Players.id eq playerId }
            .single()[Players.totalPoints]
    }
}

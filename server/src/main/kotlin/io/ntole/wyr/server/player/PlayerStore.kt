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
        }

        return Player(id = id, totalPoints = 0)
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
                )
            }

    /**
     * Adds [points] to the player's total and returns the new total. Must run inside a transaction.
     *
     * The addition happens in SQL (`total_points = total_points + n`) rather than as a read and
     * then a write, so two votes by one player landing together cannot both start from the same
     * total and lose a point. At READ COMMITTED (Postgres's default) that alone is enough: the
     * second award waits for the first's row lock, then adds to the committed total. A
     * read-then-write at that level silently drops the point.
     *
     * The server runs at REPEATABLE_READ (`DatabaseFactory`), where the increment is not enough on
     * its own. The database refuses the second of two concurrent writes to the row (SQLState
     * 40001) and the whole transaction fails. Exposed then re-runs it, but makes only 3 attempts
     * in total with no delay between them. A burst of writes to one row can use them all up, and
     * the request fails with a 500. No point is lost, because the vote rolls back with it, but the
     * answer is rejected. Whether to drop to READ COMMITTED or tune the retry is open (CLAUDE.md
     * §8b).
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

package io.ntole.wyr.server.player

import io.ntole.wyr.server.db.Players
import org.jetbrains.exposed.v1.core.eq
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
     * Looks up a player by refresh token hash, rejecting expired ones.
     *
     * Returns `null` for both "no such token" and "expired token" — the caller must not be able
     * to tell them apart, and neither should an attacker probing the endpoint.
     */
    fun findByRefreshHash(
        hash: String,
        now: Long = System.currentTimeMillis(),
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

    fun rotateRefreshToken(
        playerId: String,
        newHash: String,
        expiresAt: Long,
    ) {
        Players.update({ Players.id eq playerId }) { row ->
            row[refreshTokenHash] = newHash
            row[refreshTokenExpiresAt] = expiresAt
        }
    }

    /**
     * Adds [points] to the player's total and returns the new total. Must run inside a transaction.
     *
     * The addition happens in SQL (`total_points = total_points + n`) rather than as a read and
     * then a write, so two votes by one player landing together cannot both start from the same
     * total and lose a point. The server's REPEATABLE_READ happens to rescue a read-then-write
     * too, because the database refuses the second write and Exposed retries the transaction, but
     * at READ COMMITTED it silently drops the point. The increment must not depend on either.
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

package io.ntole.wyr.server.player

import io.ntole.wyr.server.db.Players
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.or
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
            row[Players.answersGiven] = 0
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
            ?.toPlayer()

    /**
     * Spends the refresh token whose hash is [presentedHash]: swaps it for [newHash], valid until
     * [expiresAt], and returns its player, or `null` when no refresh may spend it. Must run inside a
     * transaction.
     *
     * A refresh may spend a player's current token until it expires, and the token the last rotation
     * displaced (CLAUDE.md §8a) for [graceMillis] after that rotation, never past its own expiry; a
     * [graceMillis] of 0 accepts the current token alone. Either way the swap is the same: the token
     * current until now becomes the previous one, stamped [now], and [newHash] the current one. So a
     * token works twice at most. Spent while current, it becomes the previous one, which one more
     * refresh may spend within the grace; spent as the previous one, it is displaced by the token
     * current then, which becomes the previous one in its place, and it never works again.
     *
     * Returns `null` alike for an unknown, an expired, a spent and a displaced token past its grace —
     * the caller must not be able to tell them apart, and neither should an attacker probing the
     * endpoint.
     *
     * One `UPDATE` both checks and swaps: a compare-and-set whose `WHERE` holds everything the swap
     * relies on, with nothing read before it. At READ COMMITTED a second refresh presenting the same
     * token waits on the first's row lock and then re-checks that `WHERE` against the row the first
     * committed, so it never swaps from the state the first swapped from. Of two presenting the current
     * token, the second finds it the previous one by then and still spends it within the grace, so both
     * go through and the first's new token is the previous one; of two presenting the previous token,
     * the second finds it gone and is refused. An update by id after a read, or with the hash in its
     * `WHERE` and not the rest, lets both through from one state: two live sessions from one token, one
     * of them never displaced.
     */
    fun rotateRefreshToken(
        presentedHash: String,
        newHash: String,
        expiresAt: Long,
        graceMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): Player? {
        val spendableAsCurrent =
            (Players.refreshTokenHash eq presentedHash) and (Players.refreshTokenExpiresAt greater now)
        val spendableAsPrevious =
            (Players.previousRefreshTokenHash eq presentedHash) and
                (Players.previousRefreshTokenExpiresAt greater now) and
                (Players.previousRefreshTokenRotatedAt greater now - graceMillis)
        val spendable = if (graceMillis > 0) spendableAsCurrent or spendableAsPrevious else spendableAsCurrent

        val swapped =
            Players.update({ spendable }) { row ->
                // The right-hand columns are the row as it stood before this update, on both engines,
                // so whichever token was presented, the one current until now becomes the previous one.
                row[previousRefreshTokenHash] = refreshTokenHash
                row[previousRefreshTokenExpiresAt] = refreshTokenExpiresAt
                row[previousRefreshTokenRotatedAt] = now
                row[refreshTokenHash] = newHash
                row[refreshTokenExpiresAt] = expiresAt
            }
        if (swapped == 0) return null

        return Players
            .selectAll()
            .where { Players.refreshTokenHash eq newHash }
            .single()
            .toPlayer()
    }

    private fun ResultRow.toPlayer(): Player =
        Player(
            id = this[Players.id],
            totalPoints = this[Players.totalPoints],
            cycle = this[Players.currentCycle],
        )

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
     * Counts one more answer given by the player. Must run inside a transaction.
     *
     * An increment in SQL (`answers_given = answers_given + 1`), for the reason [addPoints] is one:
     * two answers by one player landing together must both count.
     */
    fun countAnswer(playerId: String) {
        val updated = Players.update({ Players.id eq playerId }) { row -> row[answersGiven] = answersGiven + 1 }
        check(updated == 1) { "player $playerId vanished mid-transaction" }
    }

    /**
     * Adds [points] to the player's total and returns the new total. Must run inside a transaction.
     * [points] is negative to take points back, as an unlike takes back its like's point
     * (`LikeStore.setLiked`).
     *
     * The addition happens in SQL (`total_points = total_points + n`) rather than as a read and
     * then a write, so two votes by one player landing together cannot both start from the same
     * total and lose a point, and nor can a burst of likes for one author. At the server's READ
     * COMMITTED (`DatabaseFactory`) that alone is enough, however many awards land at once: each
     * waits for the row lock of the one before, then adds to the committed total, with no retry. A
     * read-then-write at that level silently drops the point.
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

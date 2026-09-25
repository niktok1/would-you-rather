package io.ntole.wyr.server.player

import io.ntole.wyr.server.db.Players
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

object PlayerStore {
    data class Player(
        val id: String,
        val totalPoints: Int,
        /** Which pass over the questions the feed is on for this player (CLAUDE.md §8d). */
        val cycle: Int,
        /**
         * The player's account name, lower-cased, or null for a guest (CLAUDE.md §8a, *Accounts*). Once
         * set it never changes or goes: nothing unregisters a player or renames one.
         */
        val username: String? = null,
    )

    /**
     * Mints a guest, with no session yet: the mint opens its first in the same transaction
     * (`SessionStore.open`). Must run inside a transaction.
     */
    fun createGuest(): Player {
        val id = UUID.randomUUID().toString()

        Players.insert { row ->
            row[Players.id] = id
            row[Players.createdAt] = System.currentTimeMillis()
            row[Players.totalPoints] = 0
            row[Players.answersGiven] = 0
            row[Players.currentCycle] = Players.FIRST_CYCLE
        }

        return Player(id = id, totalPoints = 0, cycle = Players.FIRST_CYCLE)
    }

    /**
     * The player [id], or null when there is none. Names only the columns it reads, never the unused
     * ones a later migration drops (`Players`), so this build still runs once they are gone.
     */
    fun find(id: String): Player? =
        Players
            .select(Players.id, Players.totalPoints, Players.currentCycle, Players.username)
            .where { Players.id eq id }
            .limit(1)
            .firstOrNull()
            ?.toPlayer()

    private fun ResultRow.toPlayer(): Player =
        Player(
            id = this[Players.id],
            totalPoints = this[Players.totalPoints],
            cycle = this[Players.currentCycle],
            username = this[Players.username],
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
     * [points] is negative to take points back, as taking a like back takes back its point
     * (`ReactionStore.set`).
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

    /**
     * Takes [points] from the player's total if they have that many, and returns whether it did. Must
     * run inside a transaction.
     *
     * A compare-and-set in SQL (CLAUDE.md §4): `total_points = total_points - n WHERE total_points >= n`.
     * The subtraction is the update itself, as [addPoints]'s addition is, and the `WHERE` repeats what
     * the spending relies on, so two spendings of a player's last point cannot both succeed: at READ
     * COMMITTED the second waits on the first's row lock, then re-checks its `WHERE` against the total
     * the first committed, and matches nothing. 0 rows updated is too few points, or no such player.
     */
    fun spend(
        playerId: String,
        points: Int,
    ): Boolean =
        Players.update({ (Players.id eq playerId) and (Players.totalPoints greaterEq points) }) { row ->
            row[totalPoints] = totalPoints - points
        } > 0
}

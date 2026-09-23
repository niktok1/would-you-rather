package io.ntole.wyr.server.question

import io.ntole.wyr.server.db.Skips
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

object SkipStore {
    /**
     * Records that [playerId] skipped [questionId] in their current cycle (CLAUDE.md §8d). Must run
     * inside a transaction.
     *
     * The question is then not due for the rest of that cycle, and is due again in the next: the
     * feed compares the cycle kept here with the player's, as it does a vote's ([QuestionStore.feed]).
     * A skip earns nothing and touches no vote, no tally and no count on the player's row. Skipping
     * again in the same cycle writes nothing. Answering after a skip is an ordinary answer, since
     * `VoteStore.cast` never reads a skip.
     *
     * The player is resolved before the write, as for a vote: a validly signed token can outlive its
     * player, and inserting first would trip the Skips foreign key instead of answering 401.
     *
     * Written as the vote is, locked and then written, rather than as an Exposed upsert, for two
     * reasons. The cycle has to be read after the lock ([currentCycle]), and an upsert is a single
     * statement, which has no after; the guard that would make up for it, updating only to a later
     * cycle, is a `WHERE` on the conflict update, which Exposed's H2 MERGE refuses. And an upsert is
     * `ON CONFLICT` on PostgreSQL but `MERGE` on H2, so the suite would not run the SQL production
     * does, where a locking read, an insert and an update are the same statements on both.
     *
     * Two first skips racing on one question both find no skip and both insert. As for two first
     * answers (`VoteStore.cast`), the second waits on the first's uncommitted key and fails on the
     * primary key once the first commits (SQLState 23505). That failure is deliberately not caught,
     * since PostgreSQL aborts a transaction at its first error: Exposed rolls back and reruns the
     * whole transaction, which finds the committed skip and treats it like any other.
     */
    fun skip(
        playerId: String,
        questionId: String,
    ) {
        if (!QuestionStore.exists(questionId)) throw ApiFailure.questionNotFound(questionId)

        if (PlayerStore.find(playerId) == null) throw ApiFailure.unauthorized("unknown player")

        val skippedIn = lockSkip(playerId, questionId)
        val cycle = currentCycle(playerId)

        // A repeat in the cycle it was already skipped in has nothing to change.
        when (skippedIn) {
            null -> insertSkip(playerId, questionId, cycle)
            cycle -> Unit
            else -> moveSkip(playerId, questionId, cycle)
        }
    }

    /**
     * The cycle the player last skipped the question in, locked until this transaction ends, or null
     * before their first skip of it. Before a first skip there is no row to lock, and two first
     * skips race on the primary key instead (see [skip]).
     */
    private fun lockSkip(
        playerId: String,
        questionId: String,
    ): Int? =
        Skips
            .select(Skips.skippedInCycle)
            .where { (Skips.playerId eq playerId) and (Skips.questionId eq questionId) }
            .forUpdate()
            .singleOrNull()
            ?.get(Skips.skippedInCycle)

    /**
     * The cycle this skip counts for, read after [lockSkip] for the reason `VoteStore.currentCycle`
     * is read after the vote's lock. A skip that waited on the lock for another skip of the same
     * question may find that the feed started the next cycle meanwhile, and it lands in that one.
     * Taken from before the wait, it would count for the cycle before, and its question would come
     * round again in the cycle it was just skipped in.
     *
     * The feed can still start a cycle between this read and the commit, and that is harmless. It
     * starts one only when nothing is due, and this skip is not committed yet, so its question was
     * already answered or skipped in the cycle read here: skipping it there as well changes nothing
     * that is due.
     */
    private fun currentCycle(playerId: String): Int =
        checkNotNull(PlayerStore.find(playerId)) { "player $playerId vanished mid-transaction" }.cycle

    private fun insertSkip(
        playerId: String,
        questionId: String,
        cycle: Int,
    ) {
        Skips.insert { row ->
            row[Skips.playerId] = playerId
            row[Skips.questionId] = questionId
            row[Skips.skippedInCycle] = cycle
        }
    }

    /** Under [lockSkip]'s lock, so an update by key is exactly the row that was read. */
    private fun moveSkip(
        playerId: String,
        questionId: String,
        cycle: Int,
    ) {
        val moved =
            Skips.update({ (Skips.playerId eq playerId) and (Skips.questionId eq questionId) }) { row ->
                row[skippedInCycle] = cycle
            }
        // Nothing deletes a skip, and this transaction holds its lock.
        check(moved == 1) { "skip by $playerId of $questionId vanished mid-transaction" }
    }
}

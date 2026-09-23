package io.ntole.wyr.server.vote

import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

object VoteStore {
    /**
     * Records an answer and scores it. Must run inside a transaction.
     *
     * A player holds one vote per question, their latest (CLAUDE.md §8d). A first answer inserts
     * it; answering again moves it to the new side and pays again, even within the cycle it was
     * last answered in (§8b leaves that to rate limiting). Either way the vote records the player's
     * current cycle, so the feed does not serve the question again until the next one. `created_at`
     * keeps when the player first answered and `answered_at` when they last did.
     *
     * Every answer carries the client's [attemptId], and the vote keeps the latest. A request that
     * repeats it is a retry of an answer already recorded, and is replayed: nothing is written, it
     * pays nothing, and it reports the stored side with the current tally and total.
     *
     * Ordering matters twice. The player is resolved *before* any write: a validly signed token
     * can outlive its player (an H2 dev server restarted with the constant dev secret still
     * accepts yesterday's tokens), and inserting first would trip the Votes foreign key instead
     * of answering 401. And the vote is written *before* the tally is counted, so the returned
     * percentages already include the player's own vote — which is what the reveal screen shows.
     *
     * Two first answers racing on one question both find no vote and both insert. The second waits
     * on the first's uncommitted key, then fails on the primary key (SQLState 23505) once it
     * commits. That failure is deliberately not caught: PostgreSQL aborts a transaction at its
     * first error, so nothing more could run in this one. It propagates, Exposed rolls back and
     * runs the whole transaction again (`Db.query` goes through `transaction`, 3 attempts). The
     * rerun finds the committed vote and treats it like any other: a different attempt moves it
     * (two answers, one vote), and the same attempt replays it.
     */
    fun cast(
        playerId: String,
        questionId: String,
        choice: OptionSide,
        attemptId: String,
        now: Long = System.currentTimeMillis(),
    ): VoteResultDto {
        if (!QuestionStore.exists(questionId)) throw ApiFailure.questionNotFound(questionId)

        val player = PlayerStore.find(playerId) ?: throw ApiFailure.unauthorized("unknown player")

        val stored = lockVote(playerId, questionId)

        if (stored != null && stored[Votes.attemptId] == attemptId) return replay(playerId, questionId, stored)

        if (stored == null) {
            insertVote(playerId, questionId, choice, attemptId, player.cycle, now)
        } else {
            moveVote(playerId, questionId, choice, attemptId, player.cycle, now)
        }

        val tally = tally(questionId)

        val totalPoints = PlayerStore.addPoints(playerId = playerId, points = Scoring.POINTS_PER_ANSWER)

        return VoteResultDto(
            questionId = questionId,
            yourChoice = choice,
            tally = tally,
            pointsAwarded = Scoring.POINTS_PER_ANSWER,
            totalPoints = totalPoints,
            replayed = false,
        )
    }

    /**
     * The player's vote on the question, locked until this transaction ends, or null before their
     * first answer.
     *
     * Locked because what happens next depends on the attempt id read here, and at READ COMMITTED a
     * plain read can be stale by the time this transaction writes: a retry arriving while its
     * attempt is still in flight would read the attempt before it, take itself for a fresh answer,
     * and pay again. `FOR UPDATE` makes it wait for the attempt to commit and then read the row that
     * attempt left, and so replay it. Before a first answer there is no row to lock, and two first
     * answers race on the primary key instead (see [cast]).
     */
    private fun lockVote(
        playerId: String,
        questionId: String,
    ): ResultRow? =
        Votes
            .select(Votes.side, Votes.attemptId)
            .where { (Votes.playerId eq playerId) and (Votes.questionId eq questionId) }
            .forUpdate()
            .singleOrNull()

    private fun replay(
        playerId: String,
        questionId: String,
        stored: ResultRow,
    ): VoteResultDto {
        // Read now, not taken from the check in cast: that ran before any wait for the lock, and the
        // attempt being replayed may have paid its point since.
        val player = checkNotNull(PlayerStore.find(playerId)) { "player $playerId vanished mid-transaction" }
        return VoteResultDto(
            questionId = questionId,
            yourChoice = OptionSide.valueOf(stored[Votes.side]),
            tally = tally(questionId),
            pointsAwarded = 0,
            totalPoints = player.totalPoints,
            replayed = true,
        )
    }

    private fun insertVote(
        playerId: String,
        questionId: String,
        choice: OptionSide,
        attemptId: String,
        cycle: Int,
        now: Long,
    ) {
        Votes.insert { row ->
            row[Votes.playerId] = playerId
            row[Votes.questionId] = questionId
            row[Votes.side] = choice.name
            row[Votes.createdAt] = now
            row[Votes.answeredAt] = now
            row[Votes.answeredInCycle] = cycle
            row[Votes.attemptId] = attemptId
        }
    }

    /** Under [lockVote]'s lock, so an update by key is exactly the row that was read. */
    private fun moveVote(
        playerId: String,
        questionId: String,
        choice: OptionSide,
        attemptId: String,
        cycle: Int,
        now: Long,
    ) {
        val moved =
            Votes.update({ (Votes.playerId eq playerId) and (Votes.questionId eq questionId) }) { row ->
                row[side] = choice.name
                row[answeredAt] = now
                row[answeredInCycle] = cycle
                row[Votes.attemptId] = attemptId
            }
        // Nothing deletes a vote, and this transaction holds its lock.
        check(moved == 1) { "vote by $playerId on $questionId vanished mid-transaction" }
    }

    /**
     * Both sides' counts, in one statement. At READ COMMITTED each statement sees the votes
     * committed before it began, so two counts could straddle another player's re-answer and count
     * their one vote on both sides, or on neither.
     */
    private fun tally(questionId: String): VoteTallyDto {
        val votes = Votes.side.count()
        val counts =
            Votes
                .select(Votes.side, votes)
                .where { Votes.questionId eq questionId }
                .groupBy(Votes.side)
                .associate { row -> row[Votes.side] to row[votes] }

        return VoteTallyDto(votesA = counts[OptionSide.A.name] ?: 0L, votesB = counts[OptionSide.B.name] ?: 0L)
    }
}

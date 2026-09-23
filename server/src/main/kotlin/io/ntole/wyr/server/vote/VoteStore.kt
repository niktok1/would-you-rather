package io.ntole.wyr.server.vote

import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

object VoteStore {
    /**
     * Records an answer and scores it. Must run inside a transaction.
     *
     * A player holds one vote per question, their latest (CLAUDE.md §8d). A first answer inserts
     * it; answering again moves it to the new side and pays again. `created_at` keeps when the
     * player first answered and `answered_at` when they last did, which is what the feed loops by.
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
     * runs the whole transaction again (`Db.query` goes through `transaction`, 3 attempts), and the
     * rerun finds the committed vote and moves it: two answers, two points, one vote.
     */
    fun cast(
        playerId: String,
        questionId: String,
        choice: OptionSide,
        now: Long = System.currentTimeMillis(),
    ): VoteResultDto {
        if (!QuestionStore.exists(questionId)) throw ApiFailure.questionNotFound(questionId)

        if (PlayerStore.find(playerId) == null) throw ApiFailure.unauthorized("unknown player")

        // A plain read is enough to choose: an update by key applies to the row as committed
        // whatever this read saw, and an insert it was wrong about fails on the key as above.
        if (hasVoted(playerId, questionId)) {
            moveVote(playerId, questionId, choice, now)
        } else {
            insertVote(playerId, questionId, choice, now)
        }

        val tally = tally(questionId)

        val totalPoints = PlayerStore.addPoints(playerId = playerId, points = Scoring.POINTS_PER_ANSWER)

        return VoteResultDto(
            questionId = questionId,
            yourChoice = choice,
            tally = tally,
            pointsAwarded = Scoring.POINTS_PER_ANSWER,
            totalPoints = totalPoints,
        )
    }

    private fun hasVoted(
        playerId: String,
        questionId: String,
    ): Boolean =
        Votes
            .selectAll()
            .where { (Votes.playerId eq playerId) and (Votes.questionId eq questionId) }
            .limit(1)
            .any()

    private fun insertVote(
        playerId: String,
        questionId: String,
        choice: OptionSide,
        now: Long,
    ) {
        Votes.insert { row ->
            row[Votes.playerId] = playerId
            row[Votes.questionId] = questionId
            row[Votes.side] = choice.name
            row[Votes.createdAt] = now
            row[Votes.answeredAt] = now
        }
    }

    private fun moveVote(
        playerId: String,
        questionId: String,
        choice: OptionSide,
        now: Long,
    ) {
        val moved =
            Votes.update({ (Votes.playerId eq playerId) and (Votes.questionId eq questionId) }) { row ->
                row[side] = choice.name
                row[answeredAt] = now
            }
        // Nothing deletes a vote, so the one found above is still there.
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

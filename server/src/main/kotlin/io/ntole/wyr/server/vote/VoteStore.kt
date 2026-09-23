package io.ntole.wyr.server.vote

import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

object VoteStore {
    /**
     * SQLState of a unique or primary key violation. It is the SQL-standard code, so H2 and
     * PostgreSQL agree on it, and neither reuses it for a foreign key failure (23506 and 23503).
     */
    private const val UNIQUE_VIOLATION = "23505"

    /**
     * Records a vote and scores it. Must run inside a transaction.
     *
     * Ordering matters twice. The player is resolved *before* the insert: a validly signed token
     * can outlive its player (an H2 dev server restarted with the constant dev secret still
     * accepts yesterday's tokens), and inserting first would trip the Votes foreign key instead
     * of answering 401. And the vote is inserted *before* the tally is counted, so the returned
     * percentages already include the player's own vote — which is what the reveal screen shows.
     */
    fun cast(
        playerId: String,
        questionId: String,
        choice: OptionSide,
    ): VoteResultDto {
        if (!QuestionStore.exists(questionId)) throw ApiFailure.questionNotFound(questionId)

        val player = PlayerStore.find(playerId) ?: throw ApiFailure.unauthorized("unknown player")

        insertVote(playerId, questionId, choice)

        val votesA = countVotes(questionId, OptionSide.A)
        val votesB = countVotes(questionId, OptionSide.B)

        val award =
            Scoring.award(
                choice = choice,
                votesA = votesA,
                votesB = votesB,
                previousStreak = player.streak,
            )

        val updated =
            PlayerStore.applyAward(
                playerId = playerId,
                pointsAwarded = award.points,
                newStreak = award.streak,
            )

        return VoteResultDto(
            questionId = questionId,
            yourChoice = choice,
            tally = VoteTallyDto(votesA = votesA, votesB = votesB),
            pointsAwarded = award.points,
            totalPoints = updated.totalPoints,
            streak = updated.streak,
        )
    }

    internal fun insertVote(
        playerId: String,
        questionId: String,
        choice: OptionSide,
    ) {
        try {
            Votes.insert { row ->
                row[Votes.playerId] = playerId
                row[Votes.questionId] = questionId
                row[Votes.side] = choice.name
                row[Votes.createdAt] = System.currentTimeMillis()
            }
        } catch (failure: ExposedSQLException) {
            // Anything but a duplicate (a foreign key, a lost connection) is a real fault, not a
            // second vote, and must surface as one rather than be quietly skipped by the client.
            if (failure.sqlState != UNIQUE_VIOLATION) throw failure

            // The composite primary key rejected it. Two requests racing for the same
            // (player, question) both get here, and exactly one of them wins — which is why the
            // check is the constraint rather than a prior SELECT.
            throw ApiFailure.alreadyVoted(questionId, failure)
        }
    }

    private fun countVotes(
        questionId: String,
        side: OptionSide,
    ): Long =
        Votes
            .selectAll()
            .where { (Votes.questionId eq questionId) and (Votes.side eq side.name) }
            .count()
}

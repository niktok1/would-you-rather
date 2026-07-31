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
     * Records a vote and scores it. Must run inside a transaction.
     *
     * Ordering matters: the vote is inserted *before* the tally is counted, so the returned
     * percentages already include the player's own vote — which is what the reveal screen shows.
     */
    fun cast(
        playerId: String,
        questionId: String,
        choice: OptionSide,
    ): VoteResultDto {
        if (!QuestionStore.exists(questionId)) throw ApiFailure.questionNotFound(questionId)

        insertVote(playerId, questionId, choice)

        val votesA = countVotes(questionId, OptionSide.A)
        val votesB = countVotes(questionId, OptionSide.B)

        val player = PlayerStore.find(playerId) ?: throw ApiFailure.unauthorized("unknown player")

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

    private fun insertVote(
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
        } catch (duplicate: ExposedSQLException) {
            // The composite primary key rejected it. Two requests racing for the same
            // (player, question) both get here, and exactly one of them wins — which is why the
            // check is the constraint rather than a prior SELECT.
            throw ApiFailure.alreadyVoted(questionId, duplicate)
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

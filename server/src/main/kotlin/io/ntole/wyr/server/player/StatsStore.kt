package io.ntole.wyr.server.player

import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.like.LikeStore
import io.ntole.wyr.server.question.QuestionStore
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.select

object StatsStore {
    /**
     * The player's stats (CLAUDE.md §8d), or null for a player that does not exist. Must run inside
     * a transaction.
     *
     * All of them come from one statement: the player's row, with three counts beside it, the due
     * one compared with that row's own cycle. At READ COMMITTED each statement sees what was
     * committed before it began, so read one after another they could straddle an answer by the same
     * player committing in between, and report a question answered with no answer given for it, or a
     * like on one of their questions with no point paid for it. As one, they are all from before that
     * answer or like or all from after it, as the tally's two counts are (`VoteStore`). So the total
     * is always exactly what the answers given and the likes received earned (`Scoring`).
     *
     * The cycle is read, never started. The feed starts the next one when it finds nothing due
     * ([QuestionStore.feed]), so between the answer that finishes a cycle and the next feed request
     * this reports the finished cycle with nothing due in it.
     */
    fun of(playerId: String): PlayerStatsDto? {
        val questionsAnswered =
            wrapAsExpression<Long>(Votes.select(Votes.questionId.count()).where { Votes.playerId eq playerId })
        val dueThisCycle = QuestionStore.dueCount(playerId, categories = emptySet(), cycle = Players.currentCycle)
        val likesReceived = LikeStore.receivedBy(playerId)

        return Players
            .select(
                Players.id,
                Players.totalPoints,
                Players.answersGiven,
                Players.currentCycle,
                questionsAnswered,
                dueThisCycle,
                likesReceived,
            ).where { Players.id eq playerId }
            .singleOrNull()
            ?.let { row ->
                PlayerStatsDto(
                    playerId = row[Players.id],
                    totalPoints = row[Players.totalPoints],
                    answersGiven = row[Players.answersGiven],
                    questionsAnswered = row.countOf(questionsAnswered),
                    cycle = row[Players.currentCycle],
                    dueThisCycle = row.countOf(dueThisCycle),
                    likesReceived = row.countOf(likesReceived),
                )
            }
    }

    /** A `COUNT` subquery's value. It always yields one row, so it is never null. */
    private fun ResultRow.countOf(expression: Expression<Long?>): Int =
        checkNotNull(this[expression]) { "a COUNT subquery came back null" }.toInt()
}

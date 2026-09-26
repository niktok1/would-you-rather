package io.ntole.wyr.server.player

import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.server.auth.IdentityProvider
import io.ntole.wyr.server.db.Identities
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.question.QuestionStore
import io.ntole.wyr.server.reaction.ReactionStore
import io.ntole.wyr.server.vote.Scoring
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.select

object StatsStore {
    /**
     * The player's stats (CLAUDE.md §8d), or null for a player that does not exist. Must run inside
     * a transaction.
     *
     * Beside the numbers, the player's username, null for a guest (CLAUDE.md §8a, *Accounts*), and
     * whether they signed in with Play Games (*Play Games sign-in*), in the same statement.
     *
     * All of them come from one statement: the player's row, with three counts and a sum beside it,
     * the due count compared with that row's own cycle. At READ COMMITTED each statement sees what was
     * committed before it began, so read one after another they could straddle an answer by the same
     * player committing in between, and report a question answered with no answer given for it, a
     * like on one of their questions with no point paid for it, or a submission's cost taken with no
     * question to show for it. As one, they are all from before that answer, like or submission or all
     * from after it, as the tally's two counts are (`VoteStore`). So the total is always exactly what
     * the answers given and the likes received earned, less the points spent (`Scoring`).
     *
     * The cycle is read, never started. The feed starts the next one when it finds nothing due
     * ([QuestionStore.feed]), so between the answer that finishes a cycle and the next feed request
     * this reports the finished cycle with nothing due in it.
     *
     * [submissionCost] is what submitting costs on this server (CLAUDE.md §8c), named beside the
     * points so the game says the cost it charges; no statement reads it.
     */
    fun of(
        playerId: String,
        submissionCost: Int = Scoring.DEFAULT_SUBMISSION_COST,
    ): PlayerStatsDto? {
        val questionsAnswered =
            wrapAsExpression<Long>(Votes.select(Votes.questionId.count()).where { Votes.playerId eq playerId })
        val dueThisCycle = QuestionStore.dueCount(playerId, categories = emptySet(), cycle = Players.currentCycle)
        val likesReceived = ReactionStore.likesReceivedBy(playerId)
        val pointsSpent = spentBy(playerId)
        val playGamesLinks =
            wrapAsExpression<Long>(
                Identities
                    .select(Identities.playerId.count())
                    .where {
                        (Identities.playerId eq playerId) and (Identities.provider eq IdentityProvider.PLAY_GAMES)
                    },
            )

        return Players
            .select(
                Players.id,
                Players.totalPoints,
                Players.answersGiven,
                Players.currentCycle,
                Players.username,
                questionsAnswered,
                dueThisCycle,
                likesReceived,
                pointsSpent,
                playGamesLinks,
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
                    pointsSpent = checkNotNull(row[pointsSpent]) { "a COALESCE came back null" },
                    username = row[Players.username],
                    playGamesLinked = row.countOf(playGamesLinks) > 0,
                    submissionCost = submissionCost,
                )
            }
    }

    /**
     * What [playerId]'s questions not rejected cost them to submit (CLAUDE.md §8c), 0 for none: an
     * expression to embed beside the total, so the two are one moment's.
     */
    private fun spentBy(playerId: String): Expression<Int?> =
        wrapAsExpression(
            Questions
                .select(Coalesce(Questions.submissionCost.sum(), intLiteral(0)))
                .where { (Questions.authorPlayerId eq playerId) and (Questions.status neq QuestionStatus.REJECTED) },
        )

    /** A `COUNT` subquery's value. It always yields one row, so it is never null. */
    private fun ResultRow.countOf(expression: Expression<Long?>): Int =
        checkNotNull(this[expression]) { "a COUNT subquery came back null" }.toInt()
}

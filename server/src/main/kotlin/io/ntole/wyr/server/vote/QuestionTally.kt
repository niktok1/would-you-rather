package io.ntole.wyr.server.vote

import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Votes
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.select

/**
 * A question's tally as the server reports it, everywhere it reports one (a vote's answer,
 * `VoteStore`, and the moderator's list, `ModerationStore.questions`): each side's made-up votes
 * ([Questions.baseVotesA], [Questions.baseVotesB], CLAUDE.md §8d, *Seeds*) and the players' latest
 * votes for it, read beside the question's row in the statement that selects [columns], so both
 * sides are one committed moment's (CLAUDE.md §4). Read apart, a re-answer committing in between
 * could count one player's vote on both sides, or on neither.
 *
 * The counts are subqueries on the row's own id, each found through `votes (question_id, side)`. One
 * instance for one statement: its expressions are what a row is read back by.
 */
internal class QuestionTally {
    private val votesForA = votesFor(OptionSide.A)
    private val votesForB = votesFor(OptionSide.B)

    /** What a statement selects for [of] to read, beside whatever else it selects of the question. */
    val columns: List<Expression<*>> = listOf(Questions.baseVotesA, Questions.baseVotesB, votesForA, votesForB)

    /** The tally of the question [row] is, read with [columns]. */
    fun of(row: ResultRow): VoteTallyDto =
        VoteTallyDto(
            votesA = row[Questions.baseVotesA] + row.countOf(votesForA),
            votesB = row[Questions.baseVotesB] + row.countOf(votesForB),
        )

    /** How many players' latest answer to the row's question is [side]. */
    private fun votesFor(side: OptionSide): Expression<Long?> =
        wrapAsExpression(
            Votes
                .select(Votes.playerId.count())
                .where { (Votes.questionId eq Questions.id) and (Votes.side eq side.name) },
        )

    /** A `COUNT` subquery's value. It always yields one row, so it is never null. */
    private fun ResultRow.countOf(expression: Expression<Long?>): Long =
        checkNotNull(this[expression]) { "a COUNT subquery came back null" }
}

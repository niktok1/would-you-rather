package io.ntole.wyr.server.question

import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Skips
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.player.PlayerStore
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Random
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.intParam
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

object QuestionStore {
    /**
     * The next batch of at most [limit] questions for [playerId] (CLAUDE.md §8d). Must run inside a
     * transaction.
     *
     * The feed runs in cycles, and every question comes back once per cycle. A question is due
     * while the player has neither answered nor skipped it in their current cycle: each of those
     * never happened, or last happened in an earlier one ([SkipStore.skip]). A batch holds only due
     * questions, in an order drawn at random for each request, so every cycle comes round in a new
     * order. It is not topped up with questions answered or skipped this cycle, which would serve
     * them twice in one.
     *
     * Once nothing is due, the player has finished the cycle, whether its last questions were
     * answered or skipped. This request starts the next one and serves the whole pool again, all of
     * it due now, in a fresh random order, so a batch is empty only when nothing in [category] is
     * [servableTo] the player at all. Two requests can both find the cycle finished.
     * [PlayerStore.startNextCycle] lets only the first start the next one, and the second serves the
     * pool it read, which is due in the cycle the first started. The one exception is a question
     * answered or skipped in that new cycle before the second read the pool: the second serves it
     * again in the cycle it was just done in. That takes two overlapping requests from one player,
     * and the client sends one at a time (`DefaultQuestionRepository`).
     *
     * A cycle is per player, not per category. When nothing in [category] is due but something
     * outside it still is, the player has not finished the cycle, and starting the next one would
     * cut short their pass over the rest. So the category's questions are served again instead, in
     * random order, and the cycle stays. Answering one again still pays (§8d, re-answering) and
     * counts for the same cycle. Those skipped this cycle are served again with the rest, so a skip
     * does not hold through a category filter; that is provisional (CLAUDE.md §8b).
     *
     * [QuestionDto.answeredBefore] means the player has a vote on the question, from any cycle. A
     * skip is no vote, so a question skipped but never answered is not answered before.
     *
     * Each batch comes from one statement. The left joins find at most one vote and one skip per
     * question (the Votes and Skips keys are both player and question), so no question appears
     * twice. The random key is rendered `RANDOM()`, which H2 and PostgreSQL both have.
     */
    fun feed(
        playerId: String,
        limit: Int,
        category: QuestionCategory?,
    ): QuestionPageDto {
        val cycle = checkNotNull(PlayerStore.find(playerId)) { "player $playerId vanished mid-transaction" }.cycle
        val current = intParam(cycle)

        val due = candidates(playerId, category, dueIn = current).randomBatch(limit)
        if (due.isNotEmpty()) return QuestionPageDto(questions = due)

        // Nothing in the category is due, so every question in it, if it has any, was answered or
        // skipped in this cycle.
        val again = candidates(playerId, category, dueIn = null).randomBatch(limit)
        val cycleFinished = category == null || candidates(playerId, category = null, dueIn = current).empty()
        if (again.isNotEmpty() && cycleFinished) PlayerStore.startNextCycle(playerId, from = cycle)

        return QuestionPageDto(questions = again)
    }

    /**
     * How many questions are due for [playerId] in [cycle], in every category: what the feed would
     * serve them, counted by the feed's own predicate so the two cannot disagree. An expression to
     * embed in a larger statement, which [cycle] may be a column of (`StatsStore`).
     */
    internal fun dueCount(
        playerId: String,
        cycle: Expression<Int>,
    ): Expression<Long?> =
        wrapAsExpression(candidates(playerId, category = null, dueIn = cycle, columns = listOf(Questions.id.count())))

    /**
     * Which questions [playerId] may be served at all, whether answered or not. This is the one
     * place that decides it, so the feed and anything that counts what a player has left agree.
     *
     * Today that is every question. The exclusions CLAUDE.md §8d plans, an author's own questions
     * and submissions a moderator has not approved, belong here.
     */
    internal fun servableTo(playerId: String): Op<Boolean> = Op.TRUE

    /**
     * The questions in [category] [servableTo] the player: only those due in [dueIn], unless it is null.
     * Selects what a batch is built from, unless [columns] asks for something else.
     */
    private fun candidates(
        playerId: String,
        category: QuestionCategory?,
        dueIn: Expression<Int>?,
        columns: List<Expression<*>> = Questions.columns + Votes.answeredInCycle,
    ): Query =
        Questions
            .join(Votes, JoinType.LEFT, Questions.id, Votes.questionId) { Votes.playerId eq playerId }
            .join(Skips, JoinType.LEFT, Questions.id, Skips.questionId) { Skips.playerId eq playerId }
            .select(columns)
            .where { servableTo(playerId) and inCategory(category) and isDue(dueIn) }

    private fun Query.randomBatch(limit: Int): List<QuestionDto> =
        orderBy(Random() to SortOrder.ASC)
            .limit(limit)
            .map { row -> toDto(row, answeredBefore = row.getOrNull(Votes.answeredInCycle) != null) }

    private fun inCategory(category: QuestionCategory?): Op<Boolean> =
        category?.let { wanted -> Questions.category eq wanted.name } ?: Op.TRUE

    /**
     * Neither answered nor skipped in [cycle]: no vote, or one from an earlier cycle, and no skip, or
     * one from an earlier cycle. A null [cycle] lets all through.
     *
     * An expression rather than a number, so the cycle can be a column as well as a value, and a
     * statement that reads the player's row can count what is due in that same statement. The feed
     * passes its cycle as a parameter.
     */
    private fun isDue(cycle: Expression<Int>?): Op<Boolean> =
        cycle?.let { current -> noneIn(Votes.answeredInCycle, current) and noneIn(Skips.skippedInCycle, current) }
            ?: Op.TRUE

    /** No left-joined row, which reads [inCycle] as null, or one from a cycle before [cycle]. */
    private fun noneIn(
        inCycle: Column<Int>,
        cycle: Expression<Int>,
    ): Op<Boolean> = inCycle.isNull() or (inCycle less cycle)

    fun exists(id: String): Boolean =
        Questions
            .selectAll()
            .where { Questions.id eq id }
            .limit(1)
            .any()

    private fun toDto(
        row: ResultRow,
        answeredBefore: Boolean,
    ): QuestionDto =
        QuestionDto(
            id = row[Questions.id],
            optionA = row[Questions.optionA],
            optionB = row[Questions.optionB],
            // A category written by an older/newer build than this one still has to read back.
            category =
                runCatching { QuestionCategory.valueOf(row[Questions.category]) }
                    .getOrDefault(QuestionCategory.RANDOM),
            answeredBefore = answeredBefore,
        )
}

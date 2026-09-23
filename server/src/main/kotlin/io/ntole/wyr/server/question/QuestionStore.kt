package io.ntole.wyr.server.question

import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.player.PlayerStore
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Random
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

object QuestionStore {
    /**
     * The next batch of at most [limit] questions for [playerId] (CLAUDE.md §8d). Must run inside a
     * transaction.
     *
     * The feed runs in cycles, and every question comes back once per cycle. A question is due
     * while the player has not answered it in their current cycle: never answered, or last answered
     * in an earlier one. A batch holds only due questions, in an order drawn at random for each
     * request, so every cycle comes round in a new order. It is not topped up with questions
     * answered this cycle, which would serve them twice in one.
     *
     * Once nothing is due, the player has finished the cycle. This request starts the next one and
     * serves the whole pool again, all of it due now, in a fresh random order, so a batch is empty
     * only when nothing in [category] is [servableTo] the player at all. Two requests can both find
     * the cycle finished. [PlayerStore.startNextCycle] lets only the first start the next one, and
     * the second serves the pool it read, which is due in the cycle the first started. The one
     * exception is a question answered in that new cycle before the second read the pool: the
     * second serves it again in the cycle it was just answered in. That takes two overlapping
     * requests from one player, and the client sends one at a time (`DefaultQuestionRepository`).
     *
     * A cycle is per player, not per category. When nothing in [category] is due but something
     * outside it still is, the player has not finished the cycle, and starting the next one would
     * cut short their pass over the rest. So the category's questions are served again instead, in
     * random order and all [QuestionDto.answeredBefore], and the cycle stays. Answering one again
     * still pays (§8d, re-answering) and counts for the same cycle.
     *
     * [QuestionDto.answeredBefore] means the player has a vote on the question, from any cycle.
     *
     * Each batch comes from one statement. The left join finds at most one vote per question (the
     * Votes key is player and question), so no question appears twice. The random key is rendered
     * `RANDOM()`, which H2 and PostgreSQL both have.
     */
    fun feed(
        playerId: String,
        limit: Int,
        category: QuestionCategory?,
    ): QuestionPageDto {
        val cycle = checkNotNull(PlayerStore.find(playerId)) { "player $playerId vanished mid-transaction" }.cycle

        val due = candidates(playerId, category, dueIn = cycle).randomBatch(limit)
        if (due.isNotEmpty()) return QuestionPageDto(questions = due)

        // Nothing in the category is due, so every question in it, if it has any, was answered in
        // this cycle.
        val again = candidates(playerId, category, dueIn = null).randomBatch(limit)
        val cycleFinished = category == null || candidates(playerId, category = null, dueIn = cycle).empty()
        if (again.isNotEmpty() && cycleFinished) PlayerStore.startNextCycle(playerId, from = cycle)

        return QuestionPageDto(questions = again)
    }

    /**
     * Which questions [playerId] may be served at all, whether answered or not. This is the one
     * place that decides it, so the feed and anything that counts what a player has left agree.
     *
     * Today that is every question. The exclusions CLAUDE.md §8d plans, an author's own questions
     * and submissions a moderator has not approved, belong here.
     */
    internal fun servableTo(playerId: String): Op<Boolean> = Op.TRUE

    /** The questions in [category] [servableTo] the player: only those due in [dueIn], unless it is null. */
    private fun candidates(
        playerId: String,
        category: QuestionCategory?,
        dueIn: Int?,
    ): Query =
        Questions
            .join(Votes, JoinType.LEFT, Questions.id, Votes.questionId) { Votes.playerId eq playerId }
            .select(Questions.columns + Votes.answeredInCycle)
            .where { servableTo(playerId) and inCategory(category) and isDue(dueIn) }

    private fun Query.randomBatch(limit: Int): List<QuestionDto> =
        orderBy(Random() to SortOrder.ASC)
            .limit(limit)
            .map { row -> toDto(row, answeredBefore = row.getOrNull(Votes.answeredInCycle) != null) }

    private fun inCategory(category: QuestionCategory?): Op<Boolean> =
        category?.let { wanted -> Questions.category eq wanted.name } ?: Op.TRUE

    /** Not yet answered in [cycle]: no vote, or one from an earlier cycle. A null [cycle] lets all through. */
    private fun isDue(cycle: Int?): Op<Boolean> =
        cycle?.let { current -> Votes.answeredInCycle.isNull() or (Votes.answeredInCycle less current) } ?: Op.TRUE

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

package io.ntole.wyr.server.question

import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.server.db.QuestionCategories
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
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intParam
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select

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
     * it due now, in a fresh random order, so a batch is empty only when nothing in [categories] is
     * [servable] at all. Two requests can both find the cycle finished.
     * [PlayerStore.startNextCycle] lets only the first start the next one, and the second serves the
     * pool it read, which is due in the cycle the first started. The one exception is a question
     * answered or skipped in that new cycle before the second read the pool: the second serves it
     * again in the cycle it was just done in. That takes two overlapping requests from one player,
     * and the client sends one at a time (`DefaultQuestionRepository`).
     *
     * A question is in [categories] when it is filed under any of them, whatever else it is filed
     * under too, and every question is when [categories] is empty (CLAUDE.md §8d, *Categories*).
     * They are one pool: while anything in any of them is due, only what is due is served. A cycle
     * is per player, not per category. When nothing in [categories] is due but something outside
     * them still is, the player has not finished the cycle, and starting the next one would cut
     * short their pass over the rest. So the questions in [categories] are served again instead, in
     * random order, and the cycle stays. Answering one again still pays (§8d, re-answering) and
     * counts for the same cycle. Those skipped this cycle are served again with the rest, so a skip
     * does not hold through a category filter; that is provisional (CLAUDE.md §8b).
     *
     * [QuestionDto.answeredBefore] means the player has a vote on the question, from any cycle. A
     * skip is no vote, so a question skipped but never answered is not answered before.
     *
     * Each batch comes from one statement. The left joins find at most one vote and one skip per
     * question (the Votes and Skips keys are both player and question), and the categories are an
     * `EXISTS` rather than a join, so no question appears twice, however many of them it has. The
     * random key is rendered `RANDOM()`, which H2 and PostgreSQL both have. The batch's categories
     * are then read in one more statement ([categoriesOf]).
     */
    fun feed(
        playerId: String,
        limit: Int,
        categories: Set<QuestionCategory>,
    ): QuestionPageDto {
        val cycle = checkNotNull(PlayerStore.find(playerId)) { "player $playerId vanished mid-transaction" }.cycle
        val current = intParam(cycle)

        val due = candidates(playerId, categories, dueIn = current).randomBatch(limit)
        if (due.isNotEmpty()) return QuestionPageDto(questions = due)

        // Nothing in the categories is due, so every question in them, if they have any, was answered
        // or skipped in this cycle.
        val again = candidates(playerId, categories, dueIn = null).randomBatch(limit)
        val cycleFinished =
            categories.isEmpty() || candidates(playerId, categories = emptySet(), dueIn = current).empty()
        if (again.isNotEmpty() && cycleFinished) PlayerStore.startNextCycle(playerId, from = cycle)

        return QuestionPageDto(questions = again)
    }

    /**
     * How many questions in [categories], every one for none, are due for [playerId] in [cycle]: what
     * the feed would serve them, counted by the feed's own predicate so the two cannot disagree, and
     * each once however many of [categories] it is filed under. An expression to embed in a larger
     * statement, which [cycle] may be a column of (`StatsStore`).
     */
    internal fun dueCount(
        playerId: String,
        categories: Set<QuestionCategory>,
        cycle: Expression<Int>,
    ): Expression<Long?> =
        wrapAsExpression(candidates(playerId, categories, dueIn = cycle, columns = listOf(Questions.id.count())))

    /**
     * Which questions [playerId] may be served at all, whether answered or not (CLAUDE.md §8d). This
     * is the one place that decides it, so the feed, anything that counts what a player has left,
     * and what a player may answer or skip ([isServable]) all agree.
     *
     * A question is servable once a moderator has approved it, to every player alike: an author is
     * served their own questions like anyone else (CLAUDE.md §8d, *Own questions*). A rule that
     * depended on the player would take them here.
     */
    internal fun servable(): Op<Boolean> = Questions.status eq QuestionStatus.APPROVED

    /**
     * The questions in [categories] (every one for none) that are [servable]: only those due in
     * [dueIn], unless it is null. Selects what a batch is built from, unless [columns] asks for
     * something else.
     */
    private fun candidates(
        playerId: String,
        categories: Set<QuestionCategory>,
        dueIn: Expression<Int>?,
        columns: List<Expression<*>> = BATCH_COLUMNS,
    ): Query =
        Questions
            .join(Votes, JoinType.LEFT, Questions.id, Votes.questionId) { Votes.playerId eq playerId }
            .join(Skips, JoinType.LEFT, Questions.id, Skips.questionId) { Skips.playerId eq playerId }
            .select(columns)
            .where { servable() and inCategories(categories) and isDue(dueIn) }

    /** What [toDto] reads, and no more: the moderation columns are not the feed's business. */
    private val BATCH_COLUMNS: List<Expression<*>> =
        listOf(Questions.id, Questions.optionA, Questions.optionB, Votes.answeredInCycle)

    private fun Query.randomBatch(limit: Int): List<QuestionDto> {
        val rows = orderBy(Random() to SortOrder.ASC).limit(limit).toList()
        if (rows.isEmpty()) return emptyList()

        val categories = categoriesOf(Questions.id inList rows.map { row -> row[Questions.id] })
        return rows.map { row ->
            toDto(
                row,
                categories = categories[row[Questions.id]].orEmpty(),
                answeredBefore = row.getOrNull(Votes.answeredInCycle) != null,
            )
        }
    }

    /**
     * Filed under any of [categories], among others or not, or anything for none. An `EXISTS`, so a
     * question filed under several of them still matches once, where a join would list it once for
     * each.
     */
    private fun inCategories(categories: Set<QuestionCategory>): Op<Boolean> {
        if (categories.isEmpty()) return Op.TRUE
        val names = categories.map { it.name }
        return exists(
            QuestionCategories
                .select(QuestionCategories.questionId)
                .where {
                    (QuestionCategories.questionId eq Questions.id) and (QuestionCategories.category inList names)
                },
        )
    }

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

    /**
     * Whether a player may answer or skip the question [id]: it exists and is [servable], due or
     * not. Any other question is not found, as far as they are concerned: one still waiting for a
     * moderator, or a rejected one. An author answers and skips their own like any other.
     *
     * A plain read that a vote or a skip then writes after, and safe without a lock because a
     * question only ever becomes servable, never stops being so: nothing deletes a question, and a
     * moderator decides only a pending one (§8d). A way to withdraw an
     * approved question would end that, and the read would then have to lock the question's row.
     */
    fun isServable(id: String): Boolean =
        Questions
            .select(Questions.id)
            .where { (Questions.id eq id) and servable() }
            .limit(1)
            .any()

    private fun toDto(
        row: ResultRow,
        categories: List<QuestionCategory>,
        answeredBefore: Boolean,
    ): QuestionDto =
        QuestionDto(
            id = row[Questions.id],
            optionA = row[Questions.optionA],
            optionB = row[Questions.optionB],
            categories = categories,
            answeredBefore = answeredBefore,
        )

    /**
     * The categories of each question [questions] picks, by question id: each once, in
     * [QuestionCategory] declaration order, which is the order the wire promises (`QuestionDto`).
     * Must run inside a transaction.
     *
     * One statement for all of them, however many questions, rather than one per question. A batch
     * reads it after the statement that chose the batch, so a change to a question's categories
     * committed in between shows here: harmless, since they are then what the question is filed
     * under. Its rows are written with the question, in one transaction, so none is ever missing.
     */
    internal fun categoriesOf(questions: Op<Boolean>): Map<String, List<QuestionCategory>> =
        QuestionCategories
            .join(Questions, JoinType.INNER, QuestionCategories.questionId, Questions.id)
            .select(QuestionCategories.questionId, QuestionCategories.category)
            .where(questions)
            .map { row -> row[QuestionCategories.questionId] to categoryOf(row[QuestionCategories.category]) }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .mapValues { (_, categories) -> categories.distinct().sorted() }

    /**
     * The category a stored [name] stands for. One written by an older or newer build than this one
     * still has to read back, and never as `UNKNOWN`, which the server does not send.
     */
    private fun categoryOf(name: String): QuestionCategory =
        runCatching { QuestionCategory.valueOf(name) }
            .getOrDefault(QuestionCategory.RANDOM)
}

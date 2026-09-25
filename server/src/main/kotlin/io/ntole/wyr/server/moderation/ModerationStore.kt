package io.ntole.wyr.server.moderation

import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.AdminQuestionPageDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.Likes
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import io.ntole.wyr.server.question.SubmissionStore
import io.ntole.wyr.server.question.standsAt
import io.ntole.wyr.server.question.statusOf
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundOr
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * What a moderator reads and decides (CLAUDE.md §8d, *Moderation*). The moderator is whoever holds
 * the server's admin token, which the moderation routes check; nothing here knows who that is, and
 * nothing records it.
 */
object ModerationStore {
    /**
     * The players' submissions that stand at [status] ([standsAt]: a retired one at
     * [QuestionStatus.RETIRED], not at approved), oldest first, at most [limit]: for
     * [QuestionStatus.PENDING], the queue a moderator works through, its head the next to decide.
     * Must run inside a transaction. Two submitted in the same millisecond come in id order, as in
     * the author's list. A seed has no author and is never listed, however it stands.
     *
     * The categories come from one more statement for all of them ([QuestionStore.categoriesOf]),
     * picked by the ids listed, which [limit] bounds. A decision committed between the two shows only
     * in the second, in the categories it filed the question under.
     */
    fun queue(
        status: QuestionStatus,
        limit: Int,
    ): List<SubmissionDto> {
        val rows =
            Questions
                .select(SubmissionStore.SUBMISSION_COLUMNS)
                .where { standsAt(status) and Questions.authorPlayerId.isNotNull() }
                .orderBy(Questions.submittedAt to SortOrder.ASC, Questions.id to SortOrder.ASC)
                .limit(limit)
                .toList()
        if (rows.isEmpty()) return emptyList()

        val categories = QuestionStore.categoriesOf(Questions.id inList rows.map { row -> row[Questions.id] })
        return rows.map { row -> SubmissionStore.toSubmission(row, categories[row[Questions.id]].orEmpty()) }
    }

    /**
     * Approves the pending question [questionId] and returns it as its author now sees it (CLAUDE.md
     * §8d). Must run inside a transaction. From its commit on, the question is servable
     * ([QuestionStore.servable]), so it is due at once for every player, its author included, in
     * whatever cycle each is on.
     *
     * [categories], when there are any, replace the author's pick, and must be checked already, by
     * `CategoryStore.checked` in this same transaction: each once, in the order of categories, and each
     * a category's. None keeps the author's. They are replaced only once [decide] has made the
     * decision, in its transaction, so they commit with the status or not at all: a refused decision
     * leaves them as they were, and of two approvals racing for one question only the winner's are
     * written.
     */
    fun approve(
        questionId: String,
        categories: List<String>,
        now: Long = System.currentTimeMillis(),
    ): SubmissionDto {
        decide(questionId, QuestionStatus.APPROVED, reason = null, now)

        if (categories.isNotEmpty()) {
            QuestionCategories.deleteWhere { QuestionCategories.questionId eq questionId }
            QuestionCategories.batchInsert(categories) { category ->
                this[QuestionCategories.questionId] = questionId
                this[QuestionCategories.category] = category
            }
        }

        return decided(questionId)
    }

    /**
     * Rejects the pending question [questionId] for [reason], already checked (`checkedRejection`),
     * and returns it as its author now sees it, with the reason (CLAUDE.md §8d). Must run inside a
     * transaction. It is served to nobody, ever: nothing moves a question on from rejected.
     *
     * The author gets back what the question cost them ([Questions.submissionCost], CLAUDE.md §8c), in
     * this transaction, as an SQL increment ([PlayerStore.addPoints]). [decide]'s update holds the
     * question's row lock until the transaction ends, so the cost read here is the one decided on, and
     * only the one rejection that wins pays it back.
     */
    fun reject(
        questionId: String,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): SubmissionDto {
        decide(questionId, QuestionStatus.REJECTED, reason, now)

        val paid =
            Questions
                .select(Questions.authorPlayerId, Questions.submissionCost)
                .where { Questions.id eq questionId }
                .single()
        val author = paid[Questions.authorPlayerId]
        if (author != null && paid[Questions.submissionCost] > 0) {
            PlayerStore.addPoints(author, points = paid[Questions.submissionCost])
        }

        return decided(questionId)
    }

    /**
     * Moves the question [questionId] from pending to [status], reviewed at [now], or refuses: 409
     * for one that is not pending, a seed included, and 404 for an id no question has.
     *
     * A decision is a read-then-write, since it must find the question pending, so it is a
     * compare-and-set (CLAUDE.md §4): the update's `WHERE` repeats `PENDING`, and 0 rows updated means
     * the question was not. At READ COMMITTED a second decision on the question waits on the first's
     * row lock, then re-checks its `WHERE` against the row the first committed. No longer pending, it
     * matches nothing and is refused. By id alone it would match, and overwrite the first decision:
     * with it, exactly one of two moderators deciding one submission wins.
     *
     * A refusal reads once more to tell the two apart. Nothing deletes a question and none goes back
     * to pending, so one found now was already decided when the update ran.
     */
    private fun decide(
        questionId: String,
        status: QuestionStatus,
        reason: String?,
        now: Long,
    ) {
        val decided =
            Questions.update({ (Questions.id eq questionId) and (Questions.status eq QuestionStatus.PENDING) }) { row ->
                row[Questions.status] = status
                row[reviewedAt] = now
                row[rejectionReason] = reason
            }
        if (decided > 0) return

        val exists =
            Questions
                .select(Questions.id)
                .where { Questions.id eq questionId }
                .limit(1)
                .any()
        throw if (exists) ApiFailure.alreadyDecided(questionId) else ApiFailure.questionNotFound(questionId)
    }

    /**
     * The question [questionId] as this transaction just decided it. The update's row lock holds until
     * the transaction ends, so no other decision can come between.
     */
    private fun decided(questionId: String): SubmissionDto {
        val row =
            Questions
                .select(SubmissionStore.SUBMISSION_COLUMNS)
                .where { Questions.id eq questionId }
                .single()
        val categories = QuestionStore.categoriesOf(Questions.id eq questionId)[questionId].orEmpty()
        return SubmissionStore.toSubmission(row, categories)
    }

    /**
     * Retires the approved question [questionId], a seed included, and returns it as the moderator's
     * list shows it (CLAUDE.md §8d, *Moderation*). Must run inside a transaction. From its commit on
     * the question is not servable ([QuestionStore.servable]): served to nobody, due for nobody, so a
     * cycle can finish without it, and a vote, skip, like or unlike of it is not found. Nothing it
     * earned is taken back: every answer's point stays, and its likes stay held, so each still counts
     * in its author's total and likes received (CLAUDE.md §8c). Refused with 409 for a question that
     * is not approved, a retired one included, and 404 for an id no question has.
     *
     * A compare-and-set (CLAUDE.md §4): the update's `WHERE` is standing at approved, so of two
     * retirements racing, the second waits on the first's row lock, finds the row retired and is
     * refused. A vote, skip or like that read the question servable just before this commits takes no
     * lock ([QuestionStore.isServable]), so it still lands after it, and the numbers answered here may
     * not count it (CLAUDE.md §8b, *Retiring a question*).
     */
    fun retire(
        questionId: String,
        now: Long = System.currentTimeMillis(),
    ): AdminQuestionDto {
        move(questionId, from = QuestionStatus.APPROVED) { row -> row[Questions.retiredAt] = now }
        return listed(questionId)
    }

    /**
     * Restores the retired question [questionId] and returns it as the moderator's list shows it,
     * approved again (CLAUDE.md §8d, *Moderation*). Must run inside a transaction. From its commit on
     * it is servable as it was before it was retired: due for every player who has neither answered
     * nor skipped it in their current cycle, since what is due reads the cycle a vote or skip was
     * made in, and nothing about those changed while it was retired. Refused with 409 for a question
     * that is not retired and 404 for an id no question has, as a compare-and-set on retired, as
     * [retire] is on approved.
     */
    fun restore(questionId: String): AdminQuestionDto {
        move(questionId, from = QuestionStatus.RETIRED) { row -> row[Questions.retiredAt] = null }
        return listed(questionId)
    }

    /**
     * Applies [change] to the question [questionId] if it stands at [from], or refuses: 409 for one
     * that does not, and 404 for an id no question has. A compare-and-set, as [decide] is: 0 rows
     * updated means the question was not at [from], and as nothing deletes a question, one found
     * afterwards was already elsewhere when the update ran.
     */
    private fun move(
        questionId: String,
        from: QuestionStatus,
        change: Questions.(UpdateStatement) -> Unit,
    ) {
        val moved = Questions.update({ (Questions.id eq questionId) and standsAt(from) }, body = change)
        if (moved > 0) return

        val exists =
            Questions
                .select(Questions.id)
                .where { Questions.id eq questionId }
                .limit(1)
                .any()
        throw if (exists) ApiFailure.wrongStatus(questionId, from) else ApiFailure.questionNotFound(questionId)
    }

    /**
     * The question [questionId] as the moderator's list shows it, read by [Listing] as a page is, in
     * the transaction that just changed it, whose row lock holds until it ends.
     */
    private fun listed(questionId: String): AdminQuestionDto {
        val listing = Listing(Questions.id eq questionId)
        return listing.toAdminQuestions(listOf(listing.query.single())).single()
    }

    /**
     * One page of every question, seeds included, as the moderator sees each (CLAUDE.md §8d,
     * *Moderation*): newest first, starting after [after] when there is one, at most [limit]. Must run
     * inside a transaction.
     *
     * A question is listed when it stands at any of [statuses] and is filed under any of [categories],
     * ids already checked (`CategoryStore.checked`), either set empty for every one, the categories
     * matched as the feed matches them ([QuestionStore.inCategories]). Two stored in the same millisecond, as every seed is, come in id
     * order, as in the author's list. [AdminQuestionPageDto.nextCursor] is the last one's place when
     * more follow it, read as one more row than [limit] asks for, and null when none does, so the last
     * page says it is the last.
     *
     * Two statements a page, however long it is ([Listing]).
     */
    fun questions(
        statuses: Set<QuestionStatus>,
        categories: Set<String>,
        after: QuestionCursor?,
        limit: Int,
    ): AdminQuestionPageDto {
        val statusFilter = if (statuses.isEmpty()) Op.TRUE else statuses.map(::standsAt).compoundOr()
        val listing = Listing(statusFilter and QuestionStore.inCategories(categories) and placedAfter(after))
        val rows =
            listing.query
                .orderBy(Questions.submittedAt to SortOrder.DESC, Questions.id to SortOrder.ASC)
                .limit(limit + 1)
                .toList()

        val page = rows.take(limit)
        val next =
            page.lastOrNull()?.takeIf { rows.size > limit }?.let { last ->
                QuestionCursor(last[Questions.submittedAt], last[Questions.id])
            }
        return AdminQuestionPageDto(questions = listing.toAdminQuestions(page), nextCursor = next?.encode())
    }

    /**
     * After [cursor] in the list's order: stored before it, or in the same millisecond with a later
     * id. Everything for none.
     */
    private fun placedAfter(cursor: QuestionCursor?): Op<Boolean> {
        if (cursor == null) return Op.TRUE
        return (Questions.submittedAt less cursor.submittedAt) or
            ((Questions.submittedAt eq cursor.submittedAt) and (Questions.id greater cursor.id))
    }

    /**
     * The questions [where] picks, as [query] reads them: their own columns, both sides' vote counts
     * and their like count, in this one statement. So the numbers of a question are one committed
     * moment's, as the tally's two counts are in a vote's answer (CLAUDE.md §4): read apart, a
     * re-answer committing in between could count one player's vote on both sides, or on neither.
     *
     * The counts are subqueries on the row's own id, each found through an index: the vote counts
     * through `votes (question_id, side)` and the like count through `likes (question_id, player_id)`.
     * The categories take one more statement for every row read ([toAdminQuestions]), so a page is two
     * statements whatever its length, never one per question.
     */
    private class Listing(
        where: Op<Boolean>,
    ) {
        private val votesForA = votesFor(OptionSide.A)
        private val votesForB = votesFor(OptionSide.B)
        private val likes: Expression<Long?> =
            wrapAsExpression(Likes.select(Likes.playerId.count()).where { Likes.questionId eq Questions.id })

        val query: Query = Questions.select(ADMIN_COLUMNS + listOf(votesForA, votesForB, likes)).where(where)

        /** [rows], read by [query], with their categories. */
        fun toAdminQuestions(rows: List<ResultRow>): List<AdminQuestionDto> {
            if (rows.isEmpty()) return emptyList()

            val categories = QuestionStore.categoriesOf(Questions.id inList rows.map { row -> row[Questions.id] })
            return rows.map { row ->
                val status = statusOf(row)
                AdminQuestionDto(
                    id = row[Questions.id],
                    optionA = row[Questions.optionA],
                    optionB = row[Questions.optionB],
                    categories = categories[row[Questions.id]].orEmpty(),
                    status = status,
                    seed = row[Questions.authorPlayerId] == null,
                    submittedAt = row[Questions.submittedAt],
                    reviewedAt = row[Questions.reviewedAt],
                    retiredAt = row[Questions.retiredAt],
                    // As in a submission: only with a rejected question, whatever the column holds.
                    rejectionReason = row[Questions.rejectionReason].takeIf { status == QuestionStatus.REJECTED },
                    tally = VoteTallyDto(votesA = row.countOf(votesForA), votesB = row.countOf(votesForB)),
                    likeCount = row.countOf(likes).toInt(),
                )
            }
        }

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

        private companion object {
            /** What [toAdminQuestions] reads of a question's own row. */
            val ADMIN_COLUMNS: List<Expression<*>> =
                listOf(
                    Questions.id,
                    Questions.optionA,
                    Questions.optionB,
                    Questions.authorPlayerId,
                    Questions.status,
                    Questions.submittedAt,
                    Questions.reviewedAt,
                    Questions.retiredAt,
                    Questions.rejectionReason,
                )
        }
    }
}

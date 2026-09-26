package io.ntole.wyr.server.moderation

import io.ntole.wyr.core.author.AuthorBlockDto
import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.AdminQuestionPageDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.core.report.AdminReportDto
import io.ntole.wyr.core.report.AdminReportListDto
import io.ntole.wyr.core.report.ReportReason
import io.ntole.wyr.core.report.ReportReasonCountDto
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Reports
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import io.ntole.wyr.server.question.SubmissionStore
import io.ntole.wyr.server.question.standsAt
import io.ntole.wyr.server.question.statusOf
import io.ntole.wyr.server.reaction.ReactionStore
import io.ntole.wyr.server.vote.QuestionTally
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundOr
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
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
        return rows.map { row ->
            SubmissionStore.toSubmission(row, categories[row[Questions.id]].orEmpty(), forModerator = true)
        }
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
        return SubmissionStore.toSubmission(row, categories, forModerator = true)
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
     * The reported questions, most reported first, then the most lately reported, then by id, at most
     * [limit] (CLAUDE.md §8d, *Reports*). Must run inside a transaction. Each is the question as
     * [questions] lists it, whatever it stands at, with its reports counted, in all and per reason, in
     * the same one statement as its own numbers, so they add up (CLAUDE.md §4). Its categories take one
     * more statement, as a page's do. A question stays listed until [dismissReports] clears it.
     */
    fun reports(limit: Int): AdminReportListDto {
        val counts = ReportCounts()
        val listing = Listing(counts.reported, extra = counts.columns)
        val rows =
            listing.query
                .orderBy(counts.total to SortOrder.DESC, counts.last to SortOrder.DESC, Questions.id to SortOrder.ASC)
                .limit(limit)
                .toList()

        val questions = listing.toAdminQuestions(rows)
        return AdminReportListDto(rows.zip(questions) { row, question -> counts.of(row, question) })
    }

    /**
     * Clears every report of [questionId] and returns how many there were (CLAUDE.md §8d, *Reports*).
     * Must run inside a transaction. The question stays as it stands, and hidden from each player who
     * reported it, since hiding is theirs. 404 for an id no question has; none to clear is no failure,
     * so a dismissal sent again, or two racing, clear it once. A report made after it is a new one.
     */
    fun dismissReports(questionId: String): Int {
        val exists =
            Questions
                .select(Questions.id)
                .where { Questions.id eq questionId }
                .limit(1)
                .any()
        if (!exists) throw ApiFailure.questionNotFound(questionId)

        return Reports.deleteWhere { Reports.questionId eq questionId }
    }

    /**
     * Blocks the author [authorId] from submitting, and rejects each of their submissions still pending
     * for [reason], already checked (`checkedReason`), paying each one's cost back as [reject] does
     * (CLAUDE.md §8c). Must run inside a transaction. 404 for an id no player has. A block of an author
     * blocked already keeps the first block's time, and rejects whatever is pending all the same. Their
     * approved questions, retired ones included, stay as they are.
     *
     * The author's row is locked first, as every submission of theirs locks it before it counts or
     * stores anything (`SubmissionStore.submit`), so none can come between (CLAUDE.md §4): one that
     * locked it first commits, and the pending questions read after the lock include it; one that
     * comes after waits, then finds the author blocked. The pending questions are locked in turn, so a
     * moderator deciding one meanwhile waits and then finds it decided, and each rejection here is the
     * one decision made.
     */
    fun blockAuthor(
        authorId: String,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): AuthorBlockDto {
        Players
            .select(Players.id)
            .where { Players.id eq authorId }
            .forUpdate()
            .singleOrNull()
            ?: throw ApiFailure.authorNotFound(authorId)
        Players.update({ (Players.id eq authorId) and Players.submissionsBlockedAt.isNull() }) { row ->
            row[submissionsBlockedAt] = now
        }

        val pending =
            Questions
                .select(Questions.id)
                .where { (Questions.authorPlayerId eq authorId) and (Questions.status eq QuestionStatus.PENDING) }
                .orderBy(Questions.submittedAt to SortOrder.ASC, Questions.id to SortOrder.ASC)
                .forUpdate()
                .map { row -> row[Questions.id] }
        // At most the pending cap of them (WyrApi.Limits.MAX_PENDING_SUBMISSIONS), so one at a time.
        pending.forEach { questionId -> reject(questionId, reason, now) }

        return AuthorBlockDto(authorId = authorId, blocked = true, rejectedSubmissions = pending.size)
    }

    /**
     * Lets the author [authorId] submit again. Must run inside a transaction. 404 for an id no player
     * has; an author who is not blocked is left as they are. What the block rejected stays rejected.
     */
    fun unblockAuthor(authorId: String): AuthorBlockDto {
        val found = Players.update({ Players.id eq authorId }) { row -> row[submissionsBlockedAt] = null }
        if (found == 0) throw ApiFailure.authorNotFound(authorId)

        return AuthorBlockDto(authorId = authorId, blocked = false)
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
     * The questions [where] picks, as [query] reads them: their own columns, their tally as a vote's
     * answer reports it, made-up votes included ([QuestionTally]), and their like and dislike counts,
     * in this one statement. So the numbers of a question are one committed moment's, as the tally's two sides are
     * in a vote's answer (CLAUDE.md §4): read apart, a re-answer committing in between could count one
     * player's vote on both sides, or on neither.
     *
     * The counts are subqueries on the row's own id, each found through an index: the vote counts
     * through `votes (question_id, side)` and the reaction counts through
     * `reactions (question_id, player_id)`.
     * The categories take one more statement for every row read ([toAdminQuestions]), so a page is two
     * statements whatever its length, never one per question.
     */
    private class Listing(
        where: Op<Boolean>,
        extra: List<Expression<*>> = emptyList(),
    ) {
        private val tally = QuestionTally()
        private val likes = ReactionStore.countOn(Reaction.LIKE)
        private val dislikes = ReactionStore.countOn(Reaction.DISLIKE)

        /** The rows [where] picks, with [extra] beside each question's own numbers, in the one statement. */
        val query: Query = Questions.select(ADMIN_COLUMNS + tally.columns + likes + dislikes + extra).where(where)

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
                    tally = tally.of(row),
                    likeCount = checkNotNull(row[likes]) { "a COUNT subquery came back null" }.toInt(),
                    dislikeCount = checkNotNull(row[dislikes]) { "a COUNT subquery came back null" }.toInt(),
                    authorId = row[Questions.authorPlayerId],
                )
            }
        }

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

    /**
     * A question's reports, as subqueries on the row's own id to embed beside its other numbers
     * ([reports]), each found through `reports (question_id, reason)`: how many in all, how many give
     * each reason, and when the last came.
     */
    private class ReportCounts {
        val total: Expression<Long?> =
            wrapAsExpression(Reports.select(Reports.playerId.count()).where { Reports.questionId eq Questions.id })
        val last: Expression<Long?> =
            wrapAsExpression(Reports.select(Reports.reportedAt.max()).where { Reports.questionId eq Questions.id })
        private val byReason: Map<ReportReason, Expression<Long?>> =
            ReportReason.entries.filter { it != ReportReason.UNKNOWN }.associateWith { reason ->
                wrapAsExpression(
                    Reports
                        .select(Reports.playerId.count())
                        .where { (Reports.questionId eq Questions.id) and (Reports.reason eq reason) },
                )
            }

        val columns: List<Expression<*>> = listOf(total, last) + byReason.values

        /** Whether the row's question has a report at all. */
        val reported: Op<Boolean> =
            exists(Reports.select(Reports.questionId).where { Reports.questionId eq Questions.id })

        /** [question], read with these counts in [row]. */
        fun of(
            row: ResultRow,
            question: AdminQuestionDto,
        ): AdminReportDto {
            val reasons =
                byReason
                    .map { (reason, count) -> ReportReasonCountDto(reason, row.countOf(count)) }
                    .filter { it.count > 0 }
                    .sortedByDescending { it.count }
            return AdminReportDto(
                question = question,
                reportCount = row.countOf(total),
                reasons = reasons,
                lastReportedAt = checkNotNull(row[last]) { "a reported question has no last report" },
            )
        }

        private fun ResultRow.countOf(count: Expression<Long?>): Int =
            checkNotNull(this[count]) { "a COUNT subquery came back null" }.toInt()
    }
}

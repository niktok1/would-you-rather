package io.ntole.wyr.server.moderation

import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import io.ntole.wyr.server.question.SubmissionStore
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
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
     * The players' submissions that stand at [status], oldest first, at most [limit]: for
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
                .where { (Questions.status eq status) and Questions.authorPlayerId.isNotNull() }
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
     * [categories], when there are any, replace the author's pick, and must be checked already
     * (`checkedApproval`): each once, in declaration order, none of them `UNKNOWN`. None keeps the
     * author's. They are replaced only once [decide] has made the decision, in its transaction, so
     * they commit with the status or not at all: a refused decision leaves them as they were, and of
     * two approvals racing for one question only the winner's are written.
     */
    fun approve(
        questionId: String,
        categories: List<QuestionCategory>,
        now: Long = System.currentTimeMillis(),
    ): SubmissionDto {
        decide(questionId, QuestionStatus.APPROVED, reason = null, now)

        if (categories.isNotEmpty()) {
            QuestionCategories.deleteWhere { QuestionCategories.questionId eq questionId }
            QuestionCategories.batchInsert(categories) { category ->
                this[QuestionCategories.questionId] = questionId
                this[QuestionCategories.category] = category.name
            }
        }

        return decided(questionId)
    }

    /**
     * Rejects the pending question [questionId] for [reason], already checked (`checkedRejection`),
     * and returns it as its author now sees it, with the reason (CLAUDE.md §8d). Must run inside a
     * transaction. It is served to nobody, ever: nothing moves a question on from rejected.
     */
    fun reject(
        questionId: String,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): SubmissionDto {
        decide(questionId, QuestionStatus.REJECTED, reason, now)
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
}

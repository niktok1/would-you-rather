package io.ntole.wyr.server.question

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.plugins.ApiFailure
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID

object SubmissionStore {
    /**
     * Stores [submission] as a question by [authorId], waiting for a moderator, and returns it as
     * its author sees it (CLAUDE.md §8d). Must run inside a transaction, with [submission] already
     * checked (`checkedSubmission`). It pays nothing: authors earn through likes.
     *
     * An author may have at most [WyrApi.Limits.MAX_PENDING_SUBMISSIONS] pending at once, and a
     * count then an insert is a read-then-write (CLAUDE.md §4). At READ COMMITTED two submissions
     * racing for the last place would each count the other's question as not there yet, and both
     * insert. So each first takes the author's row lock ([lockAuthor]), which every submission by
     * that author takes before counting: the second waits for the first to commit, and its count,
     * a statement that starts only then, sees the first's question. The lock is on the author, not
     * on their questions, because the question the race is about does not exist yet.
     *
     * A moderator deciding one of the author's submissions meanwhile only ever frees a place, so a
     * count that has not seen the decision refuses at worst a submission that would just have fit.
     */
    fun submit(
        authorId: String,
        submission: SubmitQuestionRequest,
        now: Long = System.currentTimeMillis(),
    ): SubmissionDto {
        lockAuthor(authorId)

        if (pendingBy(authorId) >= WyrApi.Limits.MAX_PENDING_SUBMISSIONS) {
            throw ApiFailure.submissionLimit(WyrApi.Limits.MAX_PENDING_SUBMISSIONS)
        }

        val id = UUID.randomUUID().toString()
        Questions.insert { row ->
            row[Questions.id] = id
            row[optionA] = submission.optionA
            row[optionB] = submission.optionB
            row[authorPlayerId] = authorId
            row[status] = QuestionStatus.PENDING
            row[submittedAt] = now
            row[reviewedAt] = null
            row[rejectionReason] = null
        }
        QuestionCategories.insert { row ->
            row[questionId] = id
            row[category] = submission.category.name
        }

        return SubmissionDto(
            id = id,
            optionA = submission.optionA,
            optionB = submission.optionB,
            categories = listOf(submission.category),
            status = QuestionStatus.PENDING,
            rejectionReason = null,
            submittedAt = now,
        )
    }

    /**
     * Every question [authorId] has submitted, whatever its status, newest first (CLAUDE.md §8d).
     * Must run inside a transaction. Two submitted in the same millisecond come in id order, which is
     * fixed but says nothing about which came first. Seeds have no author, so they never appear.
     *
     * A reason goes out only with a rejected question, whatever the column holds, so what the
     * contract promises does not rest on every writer of the column clearing it.
     *
     * The categories come from one more statement for all of them ([QuestionStore.categoriesOf]),
     * picked by author rather than by id, since nothing bounds how many there are. A submission
     * committed between the two is in the second only, and left out with the rest of it.
     */
    fun byAuthor(authorId: String): List<SubmissionDto> {
        val rows =
            Questions
                .select(SUBMISSION_COLUMNS)
                .where { Questions.authorPlayerId eq authorId }
                .orderBy(Questions.submittedAt to SortOrder.DESC, Questions.id to SortOrder.ASC)
                .toList()
        val categories = QuestionStore.categoriesOf(Questions.authorPlayerId eq authorId)

        return rows.map { row ->
            val status = row[Questions.status]
            SubmissionDto(
                id = row[Questions.id],
                optionA = row[Questions.optionA],
                optionB = row[Questions.optionB],
                categories = categories[row[Questions.id]].orEmpty(),
                status = status,
                rejectionReason = row[Questions.rejectionReason].takeIf { status == QuestionStatus.REJECTED },
                submittedAt = row[Questions.submittedAt],
            )
        }
    }

    private val SUBMISSION_COLUMNS =
        listOf(
            Questions.id,
            Questions.optionA,
            Questions.optionB,
            Questions.status,
            Questions.rejectionReason,
            Questions.submittedAt,
        )

    /**
     * Locks the author's row until this transaction ends. It also resolves the author, as a vote
     * resolves its player: a validly signed token can outlive its player, and inserting for one that
     * is gone would trip the Questions foreign key instead of answering 401.
     */
    private fun lockAuthor(authorId: String) {
        Players
            .select(Players.id)
            .where { Players.id eq authorId }
            .forUpdate()
            .singleOrNull()
            ?: throw ApiFailure.unauthorized("unknown player")
    }

    private fun pendingBy(authorId: String): Long {
        val pending = Questions.id.count()
        return Questions
            .select(pending)
            .where { (Questions.authorPlayerId eq authorId) and (Questions.status eq QuestionStatus.PENDING) }
            .single()[pending]
    }
}

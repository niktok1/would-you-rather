package io.ntole.wyr.server.question

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.reaction.ReactionStore
import io.ntole.wyr.server.vote.Scoring
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID

object SubmissionStore {
    /**
     * Stores [submission] as a question by [authorId], waiting for a moderator, and returns it as
     * its author sees it (CLAUDE.md §8d). Must run inside a transaction, with [submission] already
     * checked (`checkedSubmission`) and its categories by `CategoryStore.checked`, in this same
     * transaction, so they are each once, in the order of categories, and each a category's. The
     * question and its categories are written in this one transaction, and so is its cost
     * ([Scoring.SUBMISSION_COST], CLAUDE.md §8c), taken from the author's total ([PlayerStore.spend])
     * and kept on the question for a rejection to pay back. An author with fewer points is refused with
     * 409 and nothing is stored or taken. It earns nothing: authors earn through likes.
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
     *
     * An author a moderator has blocked is refused with 403, read under the same lock: a block takes the
     * author's row lock too (`ModerationStore.blockAuthor`), so a submission either commits before the
     * block, which then finds it pending and rejects it, or waits for the block and is refused.
     */
    fun submit(
        authorId: String,
        submission: SubmitQuestionRequest,
        now: Long = System.currentTimeMillis(),
    ): SubmissionDto {
        if (lockAuthor(authorId).blocked) throw ApiFailure.submissionsBlocked()

        if (pendingBy(authorId) >= WyrApi.Limits.MAX_PENDING_SUBMISSIONS) {
            throw ApiFailure.submissionLimit(WyrApi.Limits.MAX_PENDING_SUBMISSIONS)
        }

        // Under the author's row lock already, and a compare-and-set besides, so two submissions of
        // their last point cannot both pay it.
        if (!PlayerStore.spend(
                authorId,
                Scoring.SUBMISSION_COST,
            )
        ) {
            throw ApiFailure.notEnoughPoints(Scoring.SUBMISSION_COST)
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
            row[submissionCost] = Scoring.SUBMISSION_COST
        }
        QuestionCategories.batchInsert(submission.categories) { category ->
            this[QuestionCategories.questionId] = id
            this[QuestionCategories.category] = category
        }

        return SubmissionDto(
            id = id,
            optionA = submission.optionA,
            optionB = submission.optionB,
            categories = submission.categories,
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
     * Each comes with how many players like it, dislike it and have answered it, read in the one
     * statement that reads the questions, so a question's numbers are one moment's (CLAUDE.md §4). The
     * categories come from one more statement for all of them ([QuestionStore.categoriesOf]), picked
     * by author rather than by id, since nothing bounds how many there are. A submission committed
     * between the two is in the second only, and left out with the rest of it.
     */
    fun byAuthor(authorId: String): List<SubmissionDto> {
        val rows =
            Questions
                .select(SUBMISSION_COLUMNS)
                .where { Questions.authorPlayerId eq authorId }
                .orderBy(Questions.submittedAt to SortOrder.DESC, Questions.id to SortOrder.ASC)
                .toList()
        val categories = QuestionStore.categoriesOf(Questions.authorPlayerId eq authorId)

        return rows.map { row -> toSubmission(row, categories[row[Questions.id]].orEmpty()) }
    }

    /**
     * A question read with [SUBMISSION_COLUMNS], as its author sees it, filed under [categories].
     * Every reader of a submission maps it here, the author's list and the moderator's alike. A
     * retired one is [QuestionStatus.RETIRED] ([statusOf]), so its author sees that it is no longer
     * served. [forModerator] names the author by id ([SubmissionDto.authorId]), which only the admin
     * routes send, so it is off unless asked for.
     *
     * A reason goes out only with a rejected question, whatever the column holds, so what the
     * contract promises does not rest on every writer of the column clearing it.
     */
    internal fun toSubmission(
        row: ResultRow,
        categories: List<String>,
        forModerator: Boolean = false,
    ): SubmissionDto {
        val status = statusOf(row)
        return SubmissionDto(
            id = row[Questions.id],
            optionA = row[Questions.optionA],
            optionB = row[Questions.optionB],
            categories = categories,
            status = status,
            rejectionReason = row[Questions.rejectionReason].takeIf { status == QuestionStatus.REJECTED },
            submittedAt = row[Questions.submittedAt],
            likeCount = row.countOf(likeCount),
            dislikeCount = row.countOf(dislikeCount),
            answerCount = row.countOf(answerCount),
            authorId = row[Questions.authorPlayerId].takeIf { forModerator },
        )
    }

    /** A `COUNT` subquery's value. It always yields one row, so it is never null. */
    private fun ResultRow.countOf(expression: Expression<Long?>): Int =
        checkNotNull(this[expression]) { "a COUNT subquery came back null" }.toInt()

    /**
     * How many players like the row's question, dislike it, and have answered it, each counted once:
     * subqueries on its id, found through `reactions (question_id, player_id)` and the votes key. A
     * question never served has none. Its made-up votes are left out of the answers, since only a seed
     * has any and no author has a seed.
     */
    private val likeCount = ReactionStore.countOn(Reaction.LIKE)
    private val dislikeCount = ReactionStore.countOn(Reaction.DISLIKE)
    private val answerCount: Expression<Long?> =
        wrapAsExpression(Votes.select(Votes.playerId.count()).where { Votes.questionId eq Questions.id })

    /** What [toSubmission] reads, and no more. */
    internal val SUBMISSION_COLUMNS: List<Expression<*>> =
        listOf(
            Questions.id,
            Questions.optionA,
            Questions.optionB,
            Questions.authorPlayerId,
            Questions.status,
            Questions.retiredAt,
            Questions.rejectionReason,
            Questions.submittedAt,
            likeCount,
            dislikeCount,
            answerCount,
        )

    /**
     * Locks the author's row until this transaction ends, and returns what it holds. It also resolves
     * the author, as a vote resolves its player: a validly signed token can outlive its player, and
     * inserting for one that is gone would trip the Questions foreign key instead of answering 401.
     */
    private fun lockAuthor(authorId: String): LockedAuthor {
        val row =
            Players
                .select(Players.id, Players.submissionsBlockedAt)
                .where { Players.id eq authorId }
                .forUpdate()
                .singleOrNull()
                ?: throw ApiFailure.unauthorized("unknown player")
        return LockedAuthor(blocked = row[Players.submissionsBlockedAt] != null)
    }

    /** What a submission reads of its author under their row lock. */
    private class LockedAuthor(
        val blocked: Boolean,
    )

    private fun pendingBy(authorId: String): Long {
        val pending = Questions.id.count()
        return Questions
            .select(pending)
            .where { (Questions.authorPlayerId eq authorId) and (Questions.status eq QuestionStatus.PENDING) }
            .single()[pending]
    }
}

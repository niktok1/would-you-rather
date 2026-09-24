package io.ntole.wyr.server.question

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Submitting a question through the store at READ COMMITTED, set by hand as in VoteStoreTest, so a
 * race for the last pending place can be staged and a moderator's decision written straight into
 * the table.
 */
class SubmissionStoreTest {
    private val url = h2Url("wyr-submission-store-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.questionsIfEmpty()
        }
    }

    @Test
    fun `a submission is stored under its author waiting for a moderator`() {
        val author = newPlayer()

        val submission = submit(author, SubmitQuestionRequest("Fly", "Swim", QuestionCategory.SUPERPOWERS), at = 1_000L)

        val row = transaction(database) { Questions.selectAll().where { Questions.id eq submission.id }.single() }
        assertEquals("Fly" to "Swim", row[Questions.optionA] to row[Questions.optionB])
        assertEquals(QuestionCategory.SUPERPOWERS.name, row[Questions.category])
        assertEquals(author, row[Questions.authorPlayerId])
        assertEquals(QuestionStatus.PENDING, row[Questions.status])
        assertEquals(1_000L, row[Questions.submittedAt])
        assertNull(row[Questions.reviewedAt], "no moderator has seen it")
        assertNull(row[Questions.rejectionReason])
        assertEquals(QuestionStatus.PENDING to 1_000L, submission.status to submission.submittedAt)
    }

    @Test
    fun `two submissions racing for the last pending place let only one in`() {
        val author = newPlayer()
        repeat(WyrApi.Limits.MAX_PENDING_SUBMISSIONS - 1) { index -> submit(author, question(index)) }

        // The second counts while the first holds its insert of the last place uncommitted. Counted
        // without waiting for it, the second would find a place free too, and both would get in.
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { runCatching { SubmissionStore.submit(author, question(LAST)) } },
                { runCatching { SubmissionStore.submit(author, question(LAST + 1)) } },
            )

        assertEquals(true, first.isSuccess, "the first takes the last place")
        assertEquals(ErrorCode.SUBMISSION_LIMIT, (second.exceptionOrNull() as? ApiFailure)?.code)
        assertEquals(WyrApi.Limits.MAX_PENDING_SUBMISSIONS, pendingBy(author))
    }

    @Test
    fun `a moderator's decision either way frees a pending place`() {
        val author = newPlayer()
        val submitted = List(WyrApi.Limits.MAX_PENDING_SUBMISSIONS) { index -> submit(author, question(index)) }
        assertLimitReached(author)

        decide(submitted[0].id, QuestionStatus.APPROVED)
        submit(author, question(LAST))
        assertLimitReached(author)

        decide(submitted[1].id, QuestionStatus.REJECTED)
        submit(author, question(LAST + 1))
        assertLimitReached(author)
    }

    @Test
    fun `a submission by a player that does not exist is unauthorized and stores nothing`() {
        val before = transaction(database) { Questions.selectAll().count() }

        val failure = assertFailsWith<ApiFailure> { submit("no-such-player", question(0)) }

        assertEquals(ErrorCode.UNAUTHORIZED, failure.code)
        assertEquals(before, transaction(database) { Questions.selectAll().count() })
    }

    private fun newPlayer(): String =
        transaction(database) { PlayerStore.createGuest(UUID.randomUUID().toString(), Long.MAX_VALUE).id }

    private fun submit(
        author: String,
        request: SubmitQuestionRequest,
        at: Long = System.currentTimeMillis(),
    ): SubmissionDto = transaction(database) { SubmissionStore.submit(author, request, now = at) }

    private fun question(index: Int) = SubmitQuestionRequest("Option $index", "Other $index", QuestionCategory.RANDOM)

    private fun assertLimitReached(author: String) {
        val refused = assertFailsWith<ApiFailure> { submit(author, question(REFUSED)) }
        assertEquals(ErrorCode.SUBMISSION_LIMIT, refused.code)
        assertEquals(WyrApi.Limits.MAX_PENDING_SUBMISSIONS, pendingBy(author))
    }

    /** A moderator's decision, written as the moderation route will write it. */
    private fun decide(
        question: String,
        status: QuestionStatus,
    ) {
        transaction(database) {
            Questions.update({ Questions.id eq question }) { row ->
                row[Questions.status] = status
                row[reviewedAt] = System.currentTimeMillis()
                row[rejectionReason] = "not a real dilemma".takeIf { status == QuestionStatus.REJECTED }
            }
        }
    }

    private fun pendingBy(author: String): Int =
        transaction(database) {
            Questions
                .selectAll()
                .where { (Questions.authorPlayerId eq author) and (Questions.status eq QuestionStatus.PENDING) }
                .count()
                .toInt()
        }

    private companion object {
        /** Indexes for the questions submitted after a test's loop, apart from the loop's own. */
        const val LAST = 1_000
        const val REFUSED = 2_000
    }
}

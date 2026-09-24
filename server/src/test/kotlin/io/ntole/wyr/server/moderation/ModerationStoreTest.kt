package io.ntole.wyr.server.moderation

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
import io.ntole.wyr.server.db.storedCategories
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.SubmissionStore
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A moderator's queue and decisions through the store at READ COMMITTED, set by hand as in
 * SubmissionStoreTest, so two decisions on one submission can be raced.
 */
class ModerationStoreTest {
    private val url = h2Url("wyr-moderation-store-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.questionsIfEmpty()
        }
    }

    @Test
    fun `an approval files the question under the moderator's categories and records when`() {
        val author = newPlayer()
        val submitted = submit(author, listOf(QuestionCategory.FOOD), at = 1_000L)
        val chosen = listOf(QuestionCategory.ETHICS, QuestionCategory.SUPERPOWERS)

        val approved = transaction(database) { ModerationStore.approve(submitted.id, chosen, now = 5_000L) }

        assertEquals(submitted.copy(categories = chosen, status = QuestionStatus.APPROVED), approved)
        assertEquals(chosen, transaction(database) { storedCategories() }[submitted.id], "the author's rows replaced")
        val row = rowOf(submitted.id)
        assertEquals(QuestionStatus.APPROVED to 5_000L, row[Questions.status] to row[Questions.reviewedAt])
        assertNull(row[Questions.rejectionReason])
        assertEquals(
            listOf(approved),
            transaction(database) { SubmissionStore.byAuthor(author) },
            "as its author sees it",
        )
    }

    @Test
    fun `an approval naming no categories keeps the author's`() {
        val authors = listOf(QuestionCategory.FOOD, QuestionCategory.RANDOM)
        val submitted = submit(newPlayer(), authors)

        val approved = transaction(database) { ModerationStore.approve(submitted.id, emptyList()) }

        assertEquals(authors, approved.categories)
        assertEquals(authors, transaction(database) { storedCategories() }[submitted.id])
    }

    @Test
    fun `a rejection stores its reason and records when`() {
        val author = newPlayer()
        val submitted = submit(author, listOf(QuestionCategory.FOOD), at = 1_000L)

        val rejected = transaction(database) { ModerationStore.reject(submitted.id, "Not a dilemma", now = 5_000L) }

        assertEquals(submitted.copy(status = QuestionStatus.REJECTED, rejectionReason = "Not a dilemma"), rejected)
        val row = rowOf(submitted.id)
        assertEquals(QuestionStatus.REJECTED to 5_000L, row[Questions.status] to row[Questions.reviewedAt])
        assertEquals(
            listOf(rejected),
            transaction(database) { SubmissionStore.byAuthor(author) },
            "as its author sees it",
        )
    }

    @Test
    fun `a question that is not pending cannot be decided again and keeps what its decision wrote`() {
        val approved = submit(newPlayer(), listOf(QuestionCategory.FOOD))
        val rejected = submit(newPlayer(), listOf(QuestionCategory.FOOD))
        transaction(database) {
            ModerationStore.approve(approved.id, listOf(QuestionCategory.ETHICS), now = 5_000L)
            ModerationStore.reject(rejected.id, "Not a dilemma", now = 5_000L)
        }
        val before = listOf(approved.id, rejected.id, SEED).associateWith { id -> rowOf(id).decision() }
        val categoriesBefore = transaction(database) { storedCategories() }

        listOf(approved.id, rejected.id, SEED).forEach { id ->
            assertAlreadyDecided("$id approved again") {
                ModerationStore.approve(id, listOf(QuestionCategory.SUPERPOWERS), now = 9_000L)
            }
            assertAlreadyDecided("$id rejected") { ModerationStore.reject(id, "Changed my mind", now = 9_000L) }
        }

        assertEquals(before, before.keys.associateWith { id -> rowOf(id).decision() }, "status, time and reason kept")
        assertEquals(categoriesBefore, transaction(database) { storedCategories() }, "and every question's categories")
    }

    @Test
    fun `deciding an id no question has is not found`() {
        listOf<() -> SubmissionDto>(
            { ModerationStore.approve("no-such-question", listOf(QuestionCategory.FOOD)) },
            { ModerationStore.reject("no-such-question", "Not a dilemma") },
        ).forEach { decision ->
            val failure = assertFailsWith<ApiFailure> { transaction(database) { decision() } }
            assertEquals(ErrorCode.QUESTION_NOT_FOUND, failure.code)
        }
    }

    @Test
    fun `two moderators approving one submission at once let exactly one decide it`() {
        val submitted = submit(newPlayer(), listOf(QuestionCategory.FOOD))

        // The second's update waits on the first's row lock. By id alone it would then match the row
        // the first committed, approve the question again and file it under the second's categories.
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { runCatching { ModerationStore.approve(submitted.id, listOf(QuestionCategory.ETHICS)) } },
                { runCatching { ModerationStore.approve(submitted.id, listOf(QuestionCategory.SUPERPOWERS)) } },
            )

        assertEquals(listOf(QuestionCategory.ETHICS), first.getOrThrow().categories)
        assertEquals(ErrorCode.ALREADY_DECIDED, (second.exceptionOrNull() as? ApiFailure)?.code)
        assertEquals(
            listOf(QuestionCategory.ETHICS),
            transaction(database) { storedCategories() }[submitted.id],
            "the winner's categories",
        )
    }

    @Test
    fun `an approval racing a rejection of one submission lets exactly one decide it`() {
        val submitted = submit(newPlayer(), listOf(QuestionCategory.FOOD))

        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { runCatching { ModerationStore.reject(submitted.id, "Not a dilemma", now = 5_000L) } },
                { runCatching { ModerationStore.approve(submitted.id, listOf(QuestionCategory.ETHICS)) } },
            )

        assertEquals(QuestionStatus.REJECTED, first.getOrThrow().status)
        assertEquals(ErrorCode.ALREADY_DECIDED, (second.exceptionOrNull() as? ApiFailure)?.code)
        assertEquals(Decision(QuestionStatus.REJECTED, 5_000L, "Not a dilemma"), rowOf(submitted.id).decision())
        assertEquals(listOf(QuestionCategory.FOOD), transaction(database) { storedCategories() }[submitted.id])
    }

    @Test
    fun `the queue lists pending submissions oldest first and only players' own`() {
        val (one, other) = newPlayer() to newPlayer()
        val newest = submit(one, listOf(QuestionCategory.FOOD), at = 3_000L)
        val oldest = List(2) { submit(other, listOf(QuestionCategory.ETHICS), at = 1_000L) }.sortedBy { it.id }
        val middle = submit(one, listOf(QuestionCategory.FOOD, QuestionCategory.RANDOM), at = 2_000L)
        val approved = submit(other, listOf(QuestionCategory.FOOD), at = 500L)
        transaction(database) { ModerationStore.approve(approved.id, emptyList()) }

        val pending = queue(QuestionStatus.PENDING)

        assertEquals(oldest + middle + newest, pending, "as each submission was answered, one millisecond in id order")
        assertEquals(oldest, queue(QuestionStatus.PENDING, limit = 2), "the head of the queue")
        assertEquals(
            listOf(approved.id),
            queue(QuestionStatus.APPROVED).map { it.id },
            "no seed, approved from the start",
        )
    }

    @Test
    fun `the queue of rejected submissions carries each one's reason`() {
        val submitted = List(2) { index -> submit(newPlayer(), listOf(QuestionCategory.FOOD), at = 1_000L + index) }
        transaction(database) { submitted.forEach { ModerationStore.reject(it.id, "Reason for ${it.optionA}") } }

        val rejected = queue(QuestionStatus.REJECTED)

        assertEquals(submitted.map { "Reason for ${it.optionA}" }, rejected.map { it.rejectionReason })
        assertEquals(emptyList(), queue(QuestionStatus.PENDING))
    }

    @Test
    fun `the queue reads its categories in as many statements however long it is`() {
        val author = newPlayer()
        repeat(5) { submit(author, listOf(QuestionCategory.FOOD, QuestionCategory.ETHICS)) }

        val statements =
            listOf(1, 5).map { limit ->
                transaction(database) {
                    val listed = ModerationStore.queue(QuestionStatus.PENDING, limit)
                    assertEquals(
                        List(limit) { listOf(QuestionCategory.FOOD, QuestionCategory.ETHICS) },
                        listed.map { it.categories },
                    )
                    statementCount
                }
            }

        assertEquals(statements.first(), statements.last(), "one statement per submission would grow with the queue")
    }

    private fun newPlayer(): String =
        transaction(database) { PlayerStore.createGuest(UUID.randomUUID().toString(), Long.MAX_VALUE).id }

    private fun submit(
        author: String,
        categories: List<QuestionCategory>,
        at: Long = System.currentTimeMillis(),
    ): SubmissionDto {
        val tag = UUID.randomUUID().toString().take(8)
        val request = SubmitQuestionRequest("Option $tag", "Other $tag", categories)
        return transaction(database) { SubmissionStore.submit(author, request, now = at) }
    }

    private fun queue(
        status: QuestionStatus,
        limit: Int = 100,
    ): List<SubmissionDto> = transaction(database) { ModerationStore.queue(status, limit) }

    private fun rowOf(id: String): ResultRow =
        transaction(database) { Questions.selectAll().where { Questions.id eq id }.single() }

    private fun assertAlreadyDecided(
        case: String,
        decision: () -> SubmissionDto,
    ) {
        val failure = assertFailsWith<ApiFailure>(case) { transaction(database) { decision() } }
        assertEquals(ErrorCode.ALREADY_DECIDED, failure.code, case)
    }

    /** What a decision writes to a question's row. */
    private data class Decision(
        val status: QuestionStatus,
        val reviewedAt: Long?,
        val reason: String?,
    )

    private fun ResultRow.decision() =
        Decision(this[Questions.status], this[Questions.reviewedAt], this[Questions.rejectionReason])

    private companion object {
        /** A seed, approved from the start, which no moderator ever decided. */
        const val SEED = "seed-1"
    }
}

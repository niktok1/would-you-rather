package io.ntole.wyr.server.moderation

import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.AdminQuestionPageDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.filedUnder
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.tallyOf
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.question.SubmissionStore
import io.ntole.wyr.server.question.paidSubmission
import io.ntole.wyr.server.reaction.ReactionStore
import io.ntole.wyr.server.vote.VoteStore
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The moderator's list of every question (`ModerationStore.questions`, CLAUDE.md §8d, *Moderation*),
 * through the stores at READ COMMITTED as in ModerationStoreTest.
 */
class QuestionListTest {
    private val url = h2Url("wyr-question-list-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    /** When the seeds were stored, every one in the same millisecond. */
    private val seededAt: Long =
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.writeMissing(TEST_SEEDS)
            Questions
                .select(Questions.submittedAt)
                .map { it[Questions.submittedAt] }
                .distinct()
                .single()
        }

    private val seeds: List<String> = transaction(database) { Questions.select(Questions.id).map { it[Questions.id] } }

    @Test
    fun `every question is listed newest first, the seeds among them marked, each as it stands`() {
        val author = newPlayer()
        val pending = submit(author, at = seededAt + 1)
        val approved = submit(author, at = seededAt + 2, categories = listOf("ETHICS"))
        val rejected = submit(author, at = seededAt + 3)
        transaction(database) {
            ModerationStore.approve(approved.id, listOf("FOOD", "ABSURD"), now = 7_000L)
            ModerationStore.reject(rejected.id, "Not a dilemma", now = 8_000L)
        }

        val listed = everything()

        assertEquals(
            listOf(rejected.id, approved.id, pending.id) + seeds.sorted(),
            listed.map { it.id },
            "newest first, and the seeds, stored in one millisecond, in id order",
        )
        assertEquals(seeds.toSet(), listed.filter { it.seed }.map { it.id }.toSet(), "only the seeds are seeds")
        assertEquals(
            listOf(
                listed(rejected, QuestionStatus.REJECTED, reviewedAt = 8_000L, reason = "Not a dilemma"),
                listed(
                    approved,
                    QuestionStatus.APPROVED,
                    reviewedAt = 7_000L,
                    categories = listOf("FOOD", "ABSURD"),
                ),
                listed(pending, QuestionStatus.PENDING),
            ),
            listed.take(3),
        )
        val seed = listed.single { it.id == SEED }
        assertEquals(QuestionStatus.APPROVED to null, seed.status to seed.reviewedAt, "approved from the start")
        assertEquals(seededAt, seed.submittedAt)
    }

    @Test
    fun `the list narrows to the questions at any of the statuses asked for`() {
        val author = newPlayer()
        val pending = submit(author, at = seededAt + 1)
        val approved = submit(author, at = seededAt + 2)
        val rejected = submit(author, at = seededAt + 3)
        transaction(database) {
            ModerationStore.approve(approved.id, emptyList())
            ModerationStore.reject(rejected.id, "Not a dilemma")
        }

        assertEquals(listOf(pending.id), ids(statuses = setOf(QuestionStatus.PENDING)))
        assertEquals(listOf(approved.id) + seeds.sorted(), ids(statuses = setOf(QuestionStatus.APPROVED)))
        assertEquals(
            listOf(rejected.id, pending.id),
            ids(statuses = setOf(QuestionStatus.PENDING, QuestionStatus.REJECTED)),
        )
        assertEquals(ids(), ids(statuses = QuestionStatus.entries.toSet() - QuestionStatus.UNKNOWN), "all of them")
    }

    @Test
    fun `the list narrows to the questions filed under any of the categories asked for, each once`() {
        val author = newPlayer()
        val both =
            submit(author, at = seededAt + 1, categories = listOf("ETHICS", "FOOD"))
        val food = submit(author, at = seededAt + 2, categories = listOf("FOOD"))
        val filed = transaction(database) { filedUnder("ETHICS") + filedUnder("FOOD") }

        val listed = ids(categories = setOf("ETHICS", "FOOD"))

        assertEquals(listOf(food.id, both.id), listed.take(2))
        assertEquals(filed.toSet(), listed.toSet())
        assertEquals(listed.distinct(), listed, "a question filed under both is listed once")
        assertEquals(
            listOf(both.id),
            ids(statuses = setOf(QuestionStatus.PENDING), categories = setOf("ETHICS")),
            "and both filters at once",
        )
    }

    @Test
    fun `every page starts where the last one ended, and the last says it is the last`() {
        val author = newPlayer()
        repeat(3) { index -> submit(author, at = seededAt + 1 + index) }
        val everything = everything()
        assertEquals(0, everything.size % 9, "a list that ends exactly at a page's end")

        val pages = pagesOf(limit = 9)

        assertEquals(everything, pages.flatMap { it.questions }, "no question twice, and none left out")
        assertEquals(listOf(9, 9, 9), pages.map { it.questions.size })
        assertNull(pages.last().nextCursor, "no page follows a last one that is full")
        assertEquals(
            everything,
            pagesOf(limit = 4).flatMap { it.questions },
            "a tie among the seeds split across pages",
        )
    }

    @Test
    fun `a question stored between two pages does not move one already listed onto the next`() {
        val author = newPlayer()
        repeat(4) { index -> submit(author, at = seededAt + 1 + index) }
        val before = everything()
        val first = page(limit = 2)

        // Newer than every question listed, so it comes before the first page. Counted from the start,
        // the second page would begin one question early and list the first page's last again.
        submit(author, at = seededAt + 100)
        val second = page(limit = 2, after = first.nextCursor)

        assertEquals(before.take(4), first.questions + second.questions)
    }

    @Test
    fun `each question carries its tally, its like count and its dislike count`() {
        val author = newPlayer()
        val question = submit(author, at = seededAt + 1)
        transaction(database) { ModerationStore.approve(question.id, emptyList()) }
        val (one, two, three) = List(3) { newPlayer() }
        answer(one, question.id, OptionSide.A)
        answer(two, question.id, OptionSide.A)
        answer(three, question.id, OptionSide.B)
        // A re-answer moves the player's one vote.
        answer(three, question.id, OptionSide.A)
        answer(one, SEED, OptionSide.B)
        listOf(one, two).forEach { player -> react(player, question.id, Reaction.LIKE) }
        react(three, question.id, Reaction.DISLIKE)
        react(three, SEED, Reaction.DISLIKE)

        val listed = everything().associateBy { it.id }

        assertEquals(
            Triple(VoteTallyDto(votesA = 3, votesB = 0), 2, 1),
            listed.getValue(question.id).numbers(),
            "none made up",
        )
        assertEquals(Triple(tallyOf(SEED, votesA = 0, votesB = 1), 0, 1), listed.getValue(SEED).numbers())
        assertEquals(Triple(tallyOf("seed-2", votesA = 0, votesB = 0), 0, 0), listed.getValue("seed-2").numbers())
    }

    @Test
    fun `a re-answer committed while a page is read shows in both sides' counts or in neither`() {
        val player = newPlayer()
        answer(player, SEED, OptionSide.A)
        val elsewhere = Executors.newSingleThreadExecutor()
        try {
            val listed =
                transaction(database) {
                    // Moves the vote to B right after this transaction's first statement. Counted one side
                    // per statement, the one vote would show on both sides, or on neither.
                    registerInterceptor(
                        afterFirstStatement {
                            elsewhere
                                .submit(Callable { answer(player, SEED, OptionSide.B) })
                                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        },
                    )
                    ModerationStore.questions(emptySet(), emptySet(), after = null, limit = 100).questions
                }

            assertEquals(tallyOf(SEED, votesA = 1, votesB = 0), listed.single { it.id == SEED }.tally, "from before it")
            assertEquals(tallyOf(SEED, votesA = 0, votesB = 1), everything().single { it.id == SEED }.tally)
        } finally {
            elsewhere.shutdownNow()
        }
    }

    @Test
    fun `a page is read in as many statements however long it is`() {
        val (liker, critic) = newPlayer() to newPlayer()
        seeds.forEach { id ->
            answer(liker, id, OptionSide.A)
            react(liker, id, Reaction.LIKE)
            react(critic, id, Reaction.DISLIKE)
        }

        val statements =
            listOf(1, seeds.size).map { limit ->
                transaction(database) {
                    val listed = ModerationStore.questions(emptySet(), emptySet(), after = null, limit = limit)
                    assertEquals(limit, listed.questions.size)
                    assertTrue(
                        listed.questions.all {
                            it.numbers() ==
                                Triple(tallyOf(it.id, votesA = 1, votesB = 0), 1, 1)
                        },
                    )
                    assertTrue(listed.questions.all { it.categories.isNotEmpty() })
                    statementCount
                }
            }

        assertEquals(statements.first(), statements.last(), "one statement per question would grow with the page")
    }

    private fun newPlayer(): String = transaction(database) { PlayerStore.createGuest().id }

    private fun submit(
        author: String,
        at: Long,
        categories: List<String> = listOf("FOOD"),
    ): SubmissionDto {
        val tag = UUID.randomUUID().toString().take(8)
        val request = SubmitQuestionRequest("Option $tag", "Other $tag", categories)
        return transaction(database) { paidSubmission(author, request, now = at) }
    }

    private fun answer(
        player: String,
        questionId: String,
        side: OptionSide,
    ) {
        transaction(database) { VoteStore.cast(player, questionId, side, attemptId = UUID.randomUUID().toString()) }
    }

    private fun react(
        player: String,
        questionId: String,
        reaction: Reaction,
    ) {
        transaction(database) { ReactionStore.set(player, questionId, reaction) }
    }

    private fun page(
        limit: Int,
        after: String? = null,
        statuses: Set<QuestionStatus> = emptySet(),
        categories: Set<String> = emptySet(),
    ): AdminQuestionPageDto =
        transaction(database) {
            ModerationStore.questions(statuses, categories, after?.let(QuestionCursor::parse), limit)
        }

    /** Every page at [limit], following each one's cursor until the last. */
    private fun pagesOf(limit: Int): List<AdminQuestionPageDto> =
        generateSequence(page(limit)) { previous -> previous.nextCursor?.let { page(limit, after = it) } }.toList()

    /** Every question, in one page. */
    private fun everything(): List<AdminQuestionDto> = page(limit = 100).questions.also { assertTrue(it.size < 100) }

    private fun ids(
        statuses: Set<QuestionStatus> = emptySet(),
        categories: Set<String> = emptySet(),
    ): List<String> = page(limit = 100, statuses = statuses, categories = categories).questions.map { it.id }

    /** [submission] as the list shows it once a moderator left it at [status], with no votes or reactions. */
    private fun listed(
        submission: SubmissionDto,
        status: QuestionStatus,
        reviewedAt: Long? = null,
        reason: String? = null,
        categories: List<String> = submission.categories,
    ) = AdminQuestionDto(
        id = submission.id,
        optionA = submission.optionA,
        optionB = submission.optionB,
        categories = categories,
        status = status,
        seed = false,
        submittedAt = submission.submittedAt,
        reviewedAt = reviewedAt,
        rejectionReason = reason,
        tally = VoteTallyDto(votesA = 0, votesB = 0),
        likeCount = 0,
        dislikeCount = 0,
    )

    private fun AdminQuestionDto.numbers(): Triple<VoteTallyDto, Int, Int> = Triple(tally, likeCount, dislikeCount)

    /** Runs [action] once, after the first statement the transaction executes. */
    private fun afterFirstStatement(action: () -> Unit): StatementInterceptor =
        object : StatementInterceptor {
            private var fired = false

            override fun afterExecution(
                transaction: Transaction,
                contexts: List<StatementContext>,
                executedStatement: PreparedStatementApi,
            ) {
                if (fired) return
                fired = true
                action()
            }
        }

    private companion object {
        const val SEED = "seed-1"
        const val TIMEOUT_SECONDS = 10L
    }
}

package io.ntole.wyr.server.question

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.db.storedCategories
import io.ntole.wyr.server.moderation.ModerationStore
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.reaction.ReactionStore
import io.ntole.wyr.server.vote.Scoring
import io.ntole.wyr.server.vote.VoteStore
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
 * race for the last pending place can be staged. A moderator's decision goes through
 * ModerationStore, as the moderation routes' does.
 */
class SubmissionStoreTest {
    private val url = h2Url("wyr-submission-store-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.writeMissing(TEST_SEEDS)
        }
    }

    @Test
    fun `a submission is stored under its author waiting for a moderator`() {
        val author = newPlayer()

        val categories = listOf("FOOD", "SUPERPOWERS")

        val submission = submit(author, SubmitQuestionRequest("Fly", "Swim", categories), at = 1_000L)

        val row = transaction(database) { Questions.selectAll().where { Questions.id eq submission.id }.single() }
        assertEquals("Fly" to "Swim", row[Questions.optionA] to row[Questions.optionB])
        assertEquals(categories, transaction(database) { storedCategories() }[submission.id], "a row for each")
        assertEquals(categories, submission.categories)
        assertEquals(listOf(submission), transaction(database) { SubmissionStore.byAuthor(author) })
        assertEquals(author, row[Questions.authorPlayerId])
        assertEquals(QuestionStatus.PENDING, row[Questions.status])
        assertEquals(1_000L, row[Questions.submittedAt])
        assertNull(row[Questions.reviewedAt], "no moderator has seen it")
        assertNull(row[Questions.rejectionReason])
        assertEquals(QuestionStatus.PENDING to 1_000L, submission.status to submission.submittedAt)
    }

    @Test
    fun `the author's list counts each question's likes, dislikes and the players who answered it`() {
        val author = newPlayer()
        val approved = submit(author, question(1)).id.also { decide(it, QuestionStatus.APPROVED) }
        val pending = submit(author, question(2)).id
        val (one, two, three) = List(3) { newPlayer() }
        answer(one, approved, OptionSide.A)
        answer(two, approved, OptionSide.B)
        // A re-answer moves the player's one vote: still one player who answered.
        answer(one, approved, OptionSide.B)
        react(one, approved, Reaction.LIKE)
        react(two, approved, Reaction.LIKE)
        react(three, approved, Reaction.DISLIKE)
        // Another question's are not this one's.
        answer(three, SEED, OptionSide.A)
        react(one, SEED, Reaction.DISLIKE)

        val listed = transaction(database) { SubmissionStore.byAuthor(author) }.associateBy { it.id }

        assertEquals(Triple(2, 1, 2), listed.getValue(approved).counts(), "likes, dislikes and players who answered")
        assertEquals(Triple(0, 0, 0), listed.getValue(pending).counts(), "never served")
    }

    @Test
    fun `two submissions racing for the last pending place let only one in`() {
        val author = newPlayer()
        repeat(WyrApi.Limits.MAX_PENDING_SUBMISSIONS - 1) { index -> submit(author, question(index)) }
        // Enough for both, so only the pending cap can refuse one.
        transaction(database) { PlayerStore.addPoints(author, points = 2 * Scoring.SUBMISSION_COST) }

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
    fun `an author's submissions are listed newest first with a reason only on a rejected one`() {
        val author = newPlayer()
        val oldest = submit(author, question(1), at = 1_000L)
        val newest = submit(author, question(2), at = 3_000L)
        val middle = submit(author, question(3), at = 2_000L)
        submit(newPlayer(), question(4), at = 4_000L)
        decide(oldest.id, QuestionStatus.REJECTED)
        decide(newest.id, QuestionStatus.APPROVED)
        // A reason on an approved question, which no writer should leave and the list must not pass on.
        transaction(database) { Questions.update({ Questions.id eq newest.id }) { it[rejectionReason] = "stale" } }

        val listed = transaction(database) { SubmissionStore.byAuthor(author) }

        assertEquals(listOf(newest.id, middle.id, oldest.id), listed.map { it.id }, "newest first, only the author's")
        assertEquals(
            listOf(QuestionStatus.APPROVED, QuestionStatus.PENDING, QuestionStatus.REJECTED),
            listed.map { it.status },
        )
        assertEquals(listOf(null, null, "not a real dilemma"), listed.map { it.rejectionReason })
        assertEquals(middle, listed[1], "a pending one reads back as its submission was answered")
    }

    @Test
    fun `an author's submissions are listed with their categories in as many statements however many there are`() {
        val (one, five) = newPlayer() to newPlayer()
        submit(one, question(0))
        repeat(5) { index -> submit(five, question(index)) }

        val statements =
            listOf(1 to one, 5 to five).map { (count, author) ->
                transaction(database) {
                    val listed = SubmissionStore.byAuthor(author)
                    assertEquals(count, listed.size)
                    assertEquals(List(count) { listOf("ABSURD") }, listed.map { it.categories })
                    statementCount
                }
            }

        assertEquals(statements.first(), statements.last(), "one statement per submission would grow with the list")
    }

    @Test
    fun `submissions from the same millisecond are listed in a fixed order`() {
        val author = newPlayer()
        val ids = List(5) { index -> submit(author, question(index), at = 1_000L).id }

        assertEquals(ids.sorted(), transaction(database) { SubmissionStore.byAuthor(author) }.map { it.id })
    }

    @Test
    fun `a submission by a player that does not exist is unauthorized and stores nothing`() {
        val before = transaction(database) { Questions.selectAll().count() }

        val failure =
            assertFailsWith<ApiFailure> {
                transaction(
                    database,
                ) { SubmissionStore.submit("no-such-player", question(0)) }
            }

        assertEquals(ErrorCode.UNAUTHORIZED, failure.code)
        assertEquals(before, transaction(database) { Questions.selectAll().count() })
    }

    @Test
    fun `a submission costs its author the submission cost and keeps what it cost on the question`() {
        val author = newPlayer()
        transaction(database) { PlayerStore.addPoints(author, points = 3) }

        val submission = transaction(database) { SubmissionStore.submit(author, question(0)) }

        assertEquals(3 - Scoring.SUBMISSION_COST, totalOf(author))
        val cost = transaction(database) { Questions.selectAll().where { Questions.id eq submission.id }.single() }
        assertEquals(Scoring.SUBMISSION_COST, cost[Questions.submissionCost])
    }

    @Test
    fun `an author without the points to submit is refused and nothing is stored or taken`() {
        val author = newPlayer()
        val before = transaction(database) { Questions.selectAll().count() }

        val failure =
            assertFailsWith<ApiFailure> { transaction(database) { SubmissionStore.submit(author, question(0)) } }

        assertEquals(ErrorCode.NOT_ENOUGH_POINTS, failure.code)
        assertEquals(0, totalOf(author), "nothing taken")
        assertEquals(before, transaction(database) { Questions.selectAll().count() }, "nothing stored")
    }

    @Test
    fun `two submissions racing for an author's last point let only one in`() {
        val author = newPlayer()
        transaction(database) { PlayerStore.addPoints(author, points = Scoring.SUBMISSION_COST) }

        // The second waits on the author's row lock the first holds, then finds the point spent: taken
        // without the WHERE on the total, it would be paid a second time, from a total of none.
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { runCatching { SubmissionStore.submit(author, question(1)) } },
                { runCatching { SubmissionStore.submit(author, question(2)) } },
            )

        assertEquals(true, first.isSuccess, "the first pays the last point")
        assertEquals(ErrorCode.NOT_ENOUGH_POINTS, (second.exceptionOrNull() as? ApiFailure)?.code)
        assertEquals(0, totalOf(author))
        assertEquals(1, pendingBy(author))
    }

    private fun totalOf(player: String): Int? = transaction(database) { PlayerStore.find(player)?.totalPoints }

    private fun newPlayer(): String = transaction(database) { PlayerStore.createGuest().id }

    private fun submit(
        author: String,
        request: SubmitQuestionRequest,
        at: Long = System.currentTimeMillis(),
    ): SubmissionDto = transaction(database) { paidSubmission(author, request, now = at) }

    private fun question(index: Int) = SubmitQuestionRequest("Option $index", "Other $index", listOf("ABSURD"))

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

    /** A listed submission's like count, dislike count and how many players answered it. */
    private fun SubmissionDto.counts(): Triple<Int, Int, Int> = Triple(likeCount, dislikeCount, answerCount)

    private fun assertLimitReached(author: String) {
        val refused = assertFailsWith<ApiFailure> { submit(author, question(REFUSED)) }
        assertEquals(ErrorCode.SUBMISSION_LIMIT, refused.code)
        assertEquals(WyrApi.Limits.MAX_PENDING_SUBMISSIONS, pendingBy(author))
    }

    /** A moderator's decision, through the store the moderation routes decide with. */
    private fun decide(
        question: String,
        status: QuestionStatus,
    ) {
        transaction(database) {
            when (status) {
                QuestionStatus.APPROVED -> ModerationStore.approve(question, categories = emptyList())
                QuestionStatus.REJECTED -> ModerationStore.reject(question, reason = "not a real dilemma")
                else -> error("no moderator decides $status")
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
        const val SEED = "seed-1"

        /** Indexes for the questions submitted after a test's loop, apart from the loop's own. */
        const val LAST = 1_000
        const val REFUSED = 2_000
    }
}

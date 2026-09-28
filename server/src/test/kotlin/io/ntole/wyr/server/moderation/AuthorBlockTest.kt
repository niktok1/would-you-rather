package io.ntole.wyr.server.moderation

import io.ntole.wyr.core.author.AuthorBlockDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.SubmissionStore
import io.ntole.wyr.server.vote.Scoring
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A moderator blocking an author (CLAUDE.md §8d, *Moderation*), through the stores at READ COMMITTED as
 * in ModerationStoreTest, so a submission and a block of its author can be raced.
 */
class AuthorBlockTest {
    private val url = h2Url("wyr-author-block-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.writeMissing(TEST_SEEDS)
        }
    }

    @Test
    fun `a block rejects what is pending for its reason and pays each back and leaves the rest`() {
        val author = newPlayer(points = 3)
        val (first, second, approved) = List(3) { submit(author) }
        transaction(database) { ModerationStore.approve(approved.id, emptyList()) }

        val blocked = transaction(database) { ModerationStore.blockAuthor(author, "Spam", now = 7_000) }

        assertEquals(AuthorBlockDto(author, blocked = true, rejectedSubmissions = 2), blocked)
        val listed = transaction(database) { SubmissionStore.byAuthor(author) }.associateBy { it.id }
        listOf(first, second).forEach { rejected ->
            assertEquals(
                QuestionStatus.REJECTED to "Spam",
                listed.getValue(rejected.id).let {
                    it.status to
                        it.rejectionReason
                },
            )
        }
        assertEquals(QuestionStatus.APPROVED, listed.getValue(approved.id).status, "an approved one stays")
        assertEquals(2 * Scoring.DEFAULT_SUBMISSION_COST, pointsOf(author), "each rejection paid its cost back")
    }

    @Test
    fun `a blocked author is refused until unblocked and a refusal costs nothing`() {
        val author = newPlayer(points = 2)
        transaction(database) { ModerationStore.blockAuthor(author, "Spam") }

        val refused = assertFailsWith<ApiFailure> { submit(author) }
        assertEquals(ErrorCode.SUBMISSIONS_BLOCKED, refused.code)
        assertEquals(2, pointsOf(author), "nothing taken")
        assertEquals(emptyList(), transaction(database) { SubmissionStore.byAuthor(author) }, "nothing stored")

        val unblocked = transaction(database) { ModerationStore.unblockAuthor(author) }

        assertEquals(AuthorBlockDto(author, blocked = false), unblocked)
        assertEquals(QuestionStatus.PENDING, submit(author).status)
    }

    @Test
    fun `blocking again keeps the first block and rejects whatever is pending`() {
        val author = newPlayer(points = 1)
        transaction(database) { ModerationStore.blockAuthor(author, "Spam", now = 1_000) }

        val again = transaction(database) { ModerationStore.blockAuthor(author, "Still spam", now = 2_000) }

        assertEquals(AuthorBlockDto(author, blocked = true, rejectedSubmissions = 0), again)
        assertEquals(true, transaction(database) { PlayerStore.find(author) }?.submissionsBlocked)
        val unblockedTwice = List(2) { transaction(database) { ModerationStore.unblockAuthor(author) } }
        assertEquals(List(2) { AuthorBlockDto(author, blocked = false) }, unblockedTwice)
    }

    @Test
    fun `an author no player is is 404 AUTHOR_NOT_FOUND`() {
        val actions: List<() -> AuthorBlockDto> =
            listOf(
                { ModerationStore.blockAuthor("no-such-player", "Spam") },
                { ModerationStore.unblockAuthor("no-such-player") },
            )

        actions.forEach { action ->
            val failure = assertFailsWith<ApiFailure> { transaction(database) { action() } }
            assertEquals(ErrorCode.AUTHOR_NOT_FOUND, failure.code)
        }
    }

    @Test
    fun `a submission that committed just before a block is rejected by it`() {
        val author = newPlayer(points = 1)
        val request = request()

        val (stored, block) =
            raceBehindFirst<Any>(
                url,
                database,
                { SubmissionStore.submit(author, request) },
                { ModerationStore.blockAuthor(author, "Spam") },
            )

        val submission = assertIs<SubmissionDto>(stored)
        assertEquals(AuthorBlockDto(author, blocked = true, rejectedSubmissions = 1), block)
        val listed = transaction(database) { SubmissionStore.byAuthor(author) }.single()
        assertEquals(submission.id to QuestionStatus.REJECTED, listed.id to listed.status)
        assertEquals(Scoring.DEFAULT_SUBMISSION_COST, pointsOf(author), "and paid back")
    }

    @Test
    fun `a submission that waited on a block is refused`() {
        val author = newPlayer(points = 1)
        val request = request()

        val (_, refusal) =
            raceBehindFirst<Any?>(
                url,
                database,
                { ModerationStore.blockAuthor(author, "Spam") },
                { runCatching { SubmissionStore.submit(author, request) }.exceptionOrNull() },
            )

        assertEquals(ErrorCode.SUBMISSIONS_BLOCKED, assertIs<ApiFailure>(refusal).code)
        assertEquals(emptyList(), transaction(database) { SubmissionStore.byAuthor(author) })
        assertEquals(1, pointsOf(author))
    }

    @Test
    fun `a block of an author with nothing pending sets only the block`() {
        val author = newPlayer()

        transaction(database) { ModerationStore.blockAuthor(author, "Spam") }

        val player = transaction(database) { PlayerStore.find(author) }
        assertEquals(true, player?.submissionsBlocked)
        assertNull(transaction(database) { SubmissionStore.byAuthor(author) }.firstOrNull())
    }

    /** A guest with [points] to spend. */
    private fun newPlayer(points: Int = 0): String =
        transaction(database) {
            PlayerStore.createGuest().id.also { id -> if (points > 0) PlayerStore.addPoints(id, points) }
        }

    private fun request(): SubmitQuestionRequest {
        val tag = UUID.randomUUID().toString().take(8)
        return SubmitQuestionRequest("Option $tag", "Other $tag", listOf("FOOD"))
    }

    private fun submit(author: String): SubmissionDto =
        transaction(database) { SubmissionStore.submit(author, request()) }

    private fun pointsOf(player: String): Int? = transaction(database) { PlayerStore.find(player)?.totalPoints }
}

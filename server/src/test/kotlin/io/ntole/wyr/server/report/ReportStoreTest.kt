package io.ntole.wyr.server.report

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.report.ReportReason
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.server.db.HiddenAuthors
import io.ntole.wyr.server.db.HiddenQuestions
import io.ntole.wyr.server.db.INSERTING_INTO_HIDDEN_QUESTIONS
import io.ntole.wyr.server.db.INSERTING_INTO_REPORTS
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Reports
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.player.StatsStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import io.ntole.wyr.server.vote.VoteStore
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reports and hiding (CLAUDE.md §8d, *Reports*), driven through the stores at READ COMMITTED on the
 * first seeds, as in ReactionStoreTest, so the feed and the due count can be read straight after and
 * two reports racing staged. Each question a player submitted is written into the table as a
 * moderator's decision would leave it.
 */
class ReportStoreTest {
    private val url = h2Url("wyr-report-store-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.writeMissing(TEST_SEEDS)
        }
    }

    @Test
    fun `a report hides its question from the reporter's feed and due count and from nobody else`() {
        val (reporter, other) = newPlayer() to newPlayer()
        val dueBefore = dueFor(reporter)

        transaction(database) { ReportStore.report(reporter, SEED, ReportReason.OFFENSIVE) }

        assertFalse(SEED in feedIds(reporter), "never served to the reporter again")
        assertEquals(dueBefore - 1, dueFor(reporter), "nor due for them")
        assertTrue(SEED in feedIds(other), "everyone else is served it")
        assertEquals(mapOf(reporter to ReportReason.OFFENSIVE), reportsOf(SEED))
        assertEquals(setOf(SEED), hiddenFrom(reporter))
    }

    @Test
    fun `reporting again replaces the reason and keeps one report`() {
        val reporter = newPlayer()

        transaction(database) { ReportStore.report(reporter, SEED, ReportReason.SPAM, now = 1_000) }
        transaction(database) { ReportStore.report(reporter, SEED, ReportReason.REAL_PERSON, now = 2_000) }

        assertEquals(mapOf(reporter to ReportReason.REAL_PERSON), reportsOf(SEED))
        val reportedAt = transaction(database) { Reports.selectAll().single()[Reports.reportedAt] }
        assertEquals(2_000, reportedAt, "and the time")
        assertEquals(setOf(SEED), hiddenFrom(reporter), "hidden once")
    }

    @Test
    fun `hiding a question hides it without a report and hiding it again changes nothing`() {
        val player = newPlayer()

        repeat(2) { transaction(database) { ReportStore.hideQuestion(player, SEED) } }

        assertFalse(SEED in feedIds(player))
        assertEquals(setOf(SEED), hiddenFrom(player))
        assertEquals(emptyMap(), reportsOf(SEED), "no report")
    }

    @Test
    fun `hiding an author hides each of their questions and those approved later from that player alone`() {
        val (author, otherAuthor, player, other) = List(4) { newPlayer() }
        val first = submitted(author)
        val second = submitted(author)
        val pending = submitted(author, QuestionStatus.PENDING)
        val someoneElses = submitted(otherAuthor)

        repeat(2) { transaction(database) { ReportStore.hideAuthorOf(player, first) } }
        approve(pending)

        val served = feedIds(player)
        listOf(first, second, pending).forEach { question -> assertFalse(question in served, question) }
        assertTrue(someoneElses in served, "another author's questions stay")
        assertTrue(SEED in served, "and so do the seeds")
        assertTrue(feedIds(other).containsAll(listOf(first, second, pending)), "everyone else is served them")
        assertEquals(emptySet(), hiddenFrom(player), "no question hidden one by one")
        assertEquals(1, transaction(database) { HiddenAuthors.selectAll().count() }, "the author hidden once")
    }

    @Test
    fun `hiding the author of a seed hides only that seed`() {
        val player = newPlayer()

        transaction(database) { ReportStore.hideAuthorOf(player, SEED) }

        val served = feedIds(player)
        assertFalse(SEED in served)
        assertEquals(TEST_SEEDS.size - 1, served.size, "every other seed is served")
        assertEquals(setOf(SEED), hiddenFrom(player))
        assertEquals(0, transaction(database) { HiddenAuthors.selectAll().count() })
    }

    @Test
    fun `a player finishes a cycle without what they hid and one who hid everything is served nothing`() {
        val player = newPlayer()
        val (kept, hidden) = TEST_SEEDS.map { it.first }.let { ids -> ids.first() to ids.drop(1) }
        hidden.forEach { question -> transaction(database) { ReportStore.hideQuestion(player, question) } }

        assertEquals(listOf(kept), feedIds(player))
        answer(player, kept)
        assertEquals(0, dueFor(player), "the cycle is done with the one question left")
        assertEquals(listOf(kept), feedIds(player), "and the next one serves it again")
        assertEquals(2, cycleOf(player), "having started the next cycle")

        transaction(database) { ReportStore.hideQuestion(player, kept) }
        assertEquals(emptyList(), feedIds(player), "nothing left to serve")
        assertEquals(0, dueFor(player))
        assertEquals(2, cycleOf(player), "and no cycle started for nothing")
    }

    @Test
    fun `only a question the player may be served can be reported or hidden`() {
        val (author, player) = newPlayer() to newPlayer()
        val pending = submitted(author, QuestionStatus.PENDING)
        val actions: List<Pair<String, (String) -> Unit>> =
            listOf(
                "a report" to { question -> ReportStore.report(player, question, ReportReason.OTHER) },
                "a hide" to { question -> ReportStore.hideQuestion(player, question) },
                "an author's hide" to { question -> ReportStore.hideAuthorOf(player, question) },
            )

        actions.forEach { (case, act) ->
            listOf(pending, "no-such-question").forEach { question ->
                val failure = assertFailsWith<ApiFailure>(case) { transaction(database) { act(question) } }
                assertEquals(ErrorCode.QUESTION_NOT_FOUND, failure.code, "$case of $question")
            }
        }
        assertEquals(emptyMap(), reportsOf(pending))
        assertEquals(emptySet(), hiddenFrom(player))
    }

    @Test
    fun `a player who is gone is refused with 401 before anything is written`() {
        val failure =
            assertFailsWith<ApiFailure> {
                transaction(database) { ReportStore.report("no-such-player", SEED, ReportReason.SPAM) }
            }

        assertEquals(ErrorCode.UNAUTHORIZED, failure.code)
        assertEquals(emptyMap(), reportsOf(SEED))
    }

    @Test
    fun `two first reports racing leave one report with the later reason`() {
        val reporter = newPlayer()

        raceBehindFirst(
            url,
            database,
            { ReportStore.report(reporter, SEED, ReportReason.SPAM) },
            { ReportStore.report(reporter, SEED, ReportReason.OFFENSIVE) },
            queued = INSERTING_INTO_REPORTS,
        )

        assertEquals(mapOf(reporter to ReportReason.OFFENSIVE), reportsOf(SEED), "the rerun replaced the reason")
        assertEquals(setOf(SEED), hiddenFrom(reporter))
    }

    @Test
    fun `two first hides racing leave the question hidden once`() {
        val player = newPlayer()

        raceBehindFirst(
            url,
            database,
            { ReportStore.hideQuestion(player, SEED) },
            { ReportStore.hideQuestion(player, SEED) },
            queued = INSERTING_INTO_HIDDEN_QUESTIONS,
        )

        assertEquals(setOf(SEED), hiddenFrom(player))
    }

    private fun newPlayer(): String = transaction(database) { PlayerStore.createGuest().id }

    /** A food question by [author], stored as a moderator's decision of [status] would leave it. */
    private fun submitted(
        author: String,
        status: QuestionStatus = QuestionStatus.APPROVED,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        transaction(database) {
            Questions.insert { row ->
                row[Questions.id] = id
                row[optionA] = "A of $id"
                row[optionB] = "B of $id"
                row[authorPlayerId] = author
                row[Questions.status] = status
                row[submittedAt] = now
                row[reviewedAt] = now.takeIf { status != QuestionStatus.PENDING }
            }
            QuestionCategories.insert { row ->
                row[questionId] = id
                row[category] = "FOOD"
            }
        }
        return id
    }

    private fun approve(questionId: String) =
        transaction(database) {
            Questions.update({ Questions.id eq questionId }) { row -> row[status] = QuestionStatus.APPROVED }
        }

    private fun answer(
        player: String,
        questionId: String,
    ) = transaction(database) { VoteStore.cast(player, questionId, OptionSide.A, UUID.randomUUID().toString()) }

    private fun feedIds(player: String): List<String> =
        transaction(database) {
            QuestionStore.feed(player, WyrApi.Limits.MAX_PAGE_SIZE, categories = emptySet()).questions.map { it.id }
        }

    private fun dueFor(player: String): Int =
        checkNotNull(transaction(database) { StatsStore.of(player) }) { "no player $player" }.dueThisCycle

    private fun cycleOf(player: String): Int =
        checkNotNull(transaction(database) { PlayerStore.find(player) }) { "no player $player" }.cycle

    /** Every report of [questionId], by player, straight from the table. */
    private fun reportsOf(questionId: String): Map<String, ReportReason> =
        transaction(database) {
            Reports
                .select(Reports.playerId, Reports.reason)
                .where { Reports.questionId eq questionId }
                .associate { it[Reports.playerId] to it[Reports.reason] }
        }

    /** The questions hidden one by one from [player], straight from the table. */
    private fun hiddenFrom(player: String): Set<String> =
        transaction(database) {
            HiddenQuestions
                .select(HiddenQuestions.questionId)
                .where { HiddenQuestions.playerId eq player }
                .map { it[HiddenQuestions.questionId] }
                .toSet()
        }

    private companion object {
        const val SEED = "seed-1"
    }
}

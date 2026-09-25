package io.ntole.wyr.server.question

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.Skips
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.filedUnder
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.moderation.ModerationStore
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.player.StatsStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.vote.Scoring
import io.ntole.wyr.server.vote.VoteStore
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Which questions a player may be served, answer or skip (`QuestionStore.servable`, CLAUDE.md
 * §8d): only approved ones, and to every player alike, their authors included. Driven through the
 * stores at READ COMMITTED as in QuestionStoreTest, with each submitted question written straight
 * into the table as a submission and a moderator's decision would leave it, and a later approval
 * made through ModerationStore.
 */
class ServableQuestionsTest {
    private val url = h2Url("wyr-servable-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    private val seeds: List<String> =
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.questionsIfEmpty()
            Questions.select(Questions.id).map { it[Questions.id] }
        }

    private val foodSeeds: List<String> =
        transaction(database) {
            filedUnder(QuestionCategory.FOOD)
        }

    @Test
    fun `a question waiting for a moderator or rejected by one is served to nobody`() {
        val author = newPlayer()
        val player = newPlayer()
        submitted(author, QuestionStatus.PENDING)
        submitted(author, QuestionStatus.REJECTED)

        listOf("the author" to author, "another player" to player).forEach { (who, id) ->
            assertEquals(seeds.sorted(), feed(id).ids().sorted(), "$who is served only the seeds")
            assertEquals(seeds.size, statsOf(id).dueThisCycle, "and the due count on the feed's predicate agrees")
        }
        // Both are food, and with every food seed answered the feed serves that category again.
        foodSeeds.forEach { id -> answer(player, id) }
        assertEquals(foodSeeds.sorted(), feed(player, setOf(QuestionCategory.FOOD)).ids().sorted(), "even served again")
    }

    @Test
    fun `a question that is not approved does not hold the cycle open`() {
        val player = newPlayer()
        submitted(newPlayer(), QuestionStatus.PENDING)
        seeds.forEach { id -> answer(player, id) }
        assertEquals(0, statsOf(player).dueThisCycle)

        val next = feed(player)

        assertEquals(2, cycleOf(player), "nothing the player may be served was left, so the cycle was finished")
        assertEquals(seeds.sorted(), next.ids().sorted())
    }

    @Test
    fun `nobody can answer or skip a question that is not approved`() {
        val author = newPlayer()
        val player = newPlayer()

        listOf(QuestionStatus.PENDING, QuestionStatus.REJECTED).forEach { status ->
            val question = submitted(author, status)
            listOf(author, player).forEach { who ->
                assertNotFound("$status answered") { answer(who, question) }
                assertNotFound("$status skipped") { skip(who, question) }
            }
        }

        assertEquals(0L, transaction(database) { Votes.selectAll().count() + Skips.selectAll().count() })
        assertEquals(0, statsOf(player).totalPoints)
    }

    @Test
    fun `an author is served their own approved question like every other player`() {
        val author = newPlayer()
        val player = newPlayer()
        val own = submitted(author, QuestionStatus.APPROVED)

        assertEquals((seeds + own).sorted(), feed(author).ids().sorted())
        assertEquals(seeds.size + 1, statsOf(author).dueThisCycle)
        assertEquals((seeds + own).sorted(), feed(player).ids().sorted())
        assertEquals(seeds.size + 1, statsOf(player).dueThisCycle)
    }

    @Test
    fun `an author answers and skips their own question like any other`() {
        val author = newPlayer()
        val own = submitted(author, QuestionStatus.APPROVED)

        skip(author, own)
        assertEquals(seeds.size, statsOf(author).dueThisCycle, "skipped, so not due this cycle")
        assertEquals(Scoring.POINTS_PER_ANSWER, answer(author, own).pointsAwarded, "and paid for answering")
    }

    @Test
    fun `an approved question is due at once in every other player's current cycle`() {
        val player = newPlayer()
        seeds.forEach { id -> answer(player, id) }
        val question = submitted(newPlayer(), QuestionStatus.PENDING)
        assertEquals(0, statsOf(player).dueThisCycle, "the cycle is finished")

        approve(question)

        assertEquals(1, statsOf(player).dueThisCycle, "due in the cycle the player is on")
        assertEquals(listOf(question), feed(player).ids(), "and served in it, rather than starting the next one")
        assertEquals(1, cycleOf(player))
    }

    private fun newPlayer(): String = transaction(database) { PlayerStore.createGuest().id }

    /** A food question by [author], stored as a moderator's decision of [status] would leave it. */
    private fun submitted(
        author: String,
        status: QuestionStatus,
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
                row[rejectionReason] = "not a real dilemma".takeIf { status == QuestionStatus.REJECTED }
            }
            QuestionCategories.insert { row ->
                row[questionId] = id
                row[category] = QuestionCategory.FOOD.name
            }
        }
        return id
    }

    /** A moderator's approval, through the store the moderation routes decide with. */
    private fun approve(question: String) {
        transaction(database) { ModerationStore.approve(question, categories = emptyList()) }
    }

    private fun answer(
        player: String,
        questionId: String,
    ): VoteResultDto =
        transaction(database) {
            VoteStore.cast(player, questionId, OptionSide.A, attemptId = UUID.randomUUID().toString())
        }

    private fun skip(
        player: String,
        questionId: String,
    ) = transaction(database) { SkipStore.skip(player, questionId) }

    private fun feed(
        player: String,
        categories: Set<QuestionCategory> = emptySet(),
    ): List<QuestionDto> =
        transaction(database) { QuestionStore.feed(player, WyrApi.Limits.MAX_PAGE_SIZE, categories).questions }

    private fun statsOf(player: String): PlayerStatsDto =
        checkNotNull(transaction(database) { StatsStore.of(player) }) { "player $player has no stats" }

    private fun cycleOf(player: String): Int? = transaction(database) { PlayerStore.find(player)?.cycle }

    private fun assertNotFound(
        case: String,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<ApiFailure>(case) { block() }
        assertEquals(ErrorCode.QUESTION_NOT_FOUND, failure.code, case)
    }

    private fun List<QuestionDto>.ids(): List<String> = map { it.id }
}

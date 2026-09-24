package io.ntole.wyr.server.question

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.filedUnder
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.db.storedCategories
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.vote.Scoring
import io.ntole.wyr.server.vote.VoteStore
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The feed's cycles, driven through the stores at READ COMMITTED as in VoteStoreTest, so a player's
 * cycle can be read directly and a race between two feed requests staged.
 */
class QuestionStoreTest {
    private val url = h2Url("wyr-question-store-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    private val pool: List<String> =
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.questionsIfEmpty()
            Questions.select(Questions.id).map { it[Questions.id] }
        }

    private val food: List<String> =
        transaction(database) {
            filedUnder(QuestionCategory.FOOD)
        }

    private val stored: Map<String, List<QuestionCategory>> = transaction(database) { storedCategories() }

    @Test
    fun `a batch holds only the questions still due, each once`() {
        val player = newPlayer()
        val (due, answered) = pool.take(5) to pool.drop(5)
        answered.forEach { id -> answer(player, id) }

        val batch = feed(player)

        assertEquals(due.sorted(), batch.ids().sorted(), "no top-up with questions answered this cycle")
        assertTrue(batch.none { it.answeredBefore })
        assertTrue(feed(player, limit = 3).ids().let { short -> short.size == 3 && due.containsAll(short) })
    }

    @Test
    fun `a question filed under several categories is served once with every one of them`() {
        assertTrue(stored.values.any { it.size > 1 }, "no seed is filed under more than one category")

        val batch = feed(newPlayer())

        assertEquals(pool.sorted(), batch.ids().sorted(), "each question once, however many categories it has")
        batch.forEach { question -> assertEquals(stored[question.id], question.categories, question.id) }
        assertEquals(
            listOf(QuestionCategory.LIFESTYLE, QuestionCategory.ETHICS),
            batch.single { it.optionA == "Always tell the truth" }.categories,
            "in declaration order, not by name nor as stored",
        )
    }

    @Test
    fun `a category serves each question filed under it once whatever else it is filed under`() {
        assertTrue(food.any { id -> stored.getValue(id).size > 1 }, "no food seed is filed under another category too")

        val batch = feed(newPlayer(), category = QuestionCategory.FOOD)

        assertEquals(food.sorted(), batch.ids().sorted())
        batch.forEach { question -> assertEquals(stored[question.id], question.categories, question.id) }
    }

    @Test
    fun `a batch reads its categories in one statement however many questions it holds`() {
        val (one, all) = newPlayer() to newPlayer()

        val statements =
            listOf(1 to one, pool.size to all).map { (limit, player) ->
                transaction(database) {
                    val served = QuestionStore.feed(player, limit, category = null).questions.size
                    assertEquals(limit, served)
                    statementCount
                }
            }

        assertEquals(statements.first(), statements.last(), "one statement per question would grow with the batch")
    }

    @Test
    fun `once everything is answered the next cycle serves every question exactly once`() {
        val player = newPlayer()
        pool.forEach { id -> answer(player, id) }

        val served = mutableListOf<QuestionDto>()
        while (served.size < pool.size) {
            // Answered as served, a batch at a time, as a player going through the cycle would.
            val batch = feed(player, limit = 5)
            assertTrue(batch.isNotEmpty(), "the cycle ran dry after ${served.size} questions")
            batch.forEach { question -> answer(player, question.id) }
            served += batch
            assertEquals(2, cycleOf(player), "the first batch starts cycle 2, and the rest stay in it")
        }

        assertEquals(pool.sorted(), served.ids().sorted(), "every question once in the cycle")
        assertTrue(served.all { it.answeredBefore }, "every one was answered in cycle 1")
        assertEquals(pool.sorted(), feed(player).ids().sorted(), "and then the next cycle serves them all again")
        assertEquals(3, cycleOf(player))
    }

    @Test
    fun `every cycle comes round in a new order`() {
        val player = newPlayer()
        val firstCycle = feed(player).ids()
        // Answered in the order served, a millisecond apart, so looping by answer time would repeat it.
        firstCycle.forEachIndexed { index, id -> answer(player, id, at = 1_000L + index) }

        val secondCycle = feed(player).ids()

        assertEquals(firstCycle.sorted(), secondCycle.sorted())
        // The order is random per request, so this can fail by pure chance: two random orders of n
        // questions agree with probability 1/n!, which for the 24 seeds is about 1.6e-24.
        assertTrue(pool.size >= 12, "too few seeds for the odds above to stay negligible")
        assertNotEquals(firstCycle, secondCycle, "the second cycle repeated the first one's order")
    }

    @Test
    fun `two requests finding the cycle finished start the next one only once`() {
        val player = newPlayer()
        pool.forEach { id -> answer(player, id) }

        // The second reads cycle 1 while the first holds its start of cycle 2 uncommitted, so it
        // finds the cycle finished too, and queues on the player's row to start the next one.
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { QuestionStore.feed(player, WyrApi.Limits.MAX_PAGE_SIZE, category = null).questions },
                { QuestionStore.feed(player, WyrApi.Limits.MAX_PAGE_SIZE, category = null).questions },
            )

        assertEquals(2, cycleOf(player), "started once, not once per request")
        assertEquals(pool.sorted(), first.ids().sorted())
        assertEquals(pool.sorted(), second.ids().sorted(), "the second serves the cycle the first started")
    }

    @Test
    fun `a re-answer inside the cycle pays and leaves the question done for that cycle`() {
        val player = newPlayer()
        val question = pool.first()
        answer(player, question)

        val again = answer(player, question)

        assertEquals(Scoring.POINTS_PER_ANSWER, again.pointsAwarded)
        assertEquals(2 * Scoring.POINTS_PER_ANSWER, again.totalPoints)
        assertEquals((pool - question).sorted(), feed(player).ids().sorted(), "answered this cycle, so not due")
        assertEquals(1, cycleOf(player))
    }

    @Test
    fun `a category with nothing due is served again while the rest of the cycle is not done`() {
        val player = newPlayer()
        food.forEach { id -> answer(player, id) }

        val batch = feed(player, category = QuestionCategory.FOOD)

        assertEquals(food.sorted(), batch.ids().sorted(), "all of that category again, rather than nothing")
        assertTrue(batch.all { it.answeredBefore })
        assertEquals(1, cycleOf(player), "a cycle is the player's, and the other categories are still due")
        assertEquals((pool - food.toSet()).sorted(), feed(player).ids().sorted(), "so unfiltered only they are due")
    }

    @Test
    fun `the next cycle starts once nothing at all is due, whichever category is asked for`() {
        val player = newPlayer()
        pool.forEach { id -> answer(player, id) }

        val batch = feed(player, category = QuestionCategory.FOOD)

        assertEquals(food.sorted(), batch.ids().sorted())
        assertEquals(2, cycleOf(player))
        assertEquals(pool.sorted(), feed(player).ids().sorted(), "every category is due in the new cycle")
    }

    @Test
    fun `a request that finds no questions at all starts no cycle`() {
        val player = newPlayer()
        pool.forEach { id -> answer(player, id) }

        // UNKNOWN is never stored, so it stands for a category with no questions in it.
        assertTrue(feed(player, category = QuestionCategory.UNKNOWN).isEmpty())

        assertEquals(1, cycleOf(player), "a batch that served nothing must not start a cycle for the rest")
    }

    @Test
    fun `another player's answers do not count as this player's`() {
        val player = newPlayer()
        val other = newPlayer()
        pool.forEach { id -> answer(other, id) }
        feed(other)

        val batch = feed(player)

        assertEquals(pool.toSet(), batch.map { it.id }.toSet())
        assertEquals(pool.size, batch.size, "one row per question, whoever else answered it")
        assertFalse(batch.any { it.answeredBefore })
        assertEquals(1, cycleOf(player), "nor does their cycle")
    }

    private fun newPlayer(): String =
        transaction(database) { PlayerStore.createGuest(UUID.randomUUID().toString(), Long.MAX_VALUE).id }

    private fun answer(
        player: String,
        questionId: String,
        at: Long = System.currentTimeMillis(),
    ): VoteResultDto =
        transaction(database) {
            VoteStore.cast(player, questionId, OptionSide.A, attemptId = UUID.randomUUID().toString(), now = at)
        }

    private fun feed(
        player: String,
        limit: Int = WyrApi.Limits.MAX_PAGE_SIZE,
        category: QuestionCategory? = null,
    ): List<QuestionDto> = transaction(database) { QuestionStore.feed(player, limit, category).questions }

    private fun cycleOf(player: String): Int? = transaction(database) { PlayerStore.find(player)?.cycle }

    private fun List<QuestionDto>.ids(): List<String> = map { it.id }
}

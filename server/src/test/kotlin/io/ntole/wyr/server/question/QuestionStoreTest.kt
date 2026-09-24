package io.ntole.wyr.server.question

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.QuestionCategories
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
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

    private val lifestyle: List<String> = transaction(database) { filedUnder(QuestionCategory.LIFESTYLE) }

    /** Two categories some seeds are both filed under, and the questions in either. */
    private val foodOrLifestyle = setOf(QuestionCategory.FOOD, QuestionCategory.LIFESTYLE)
    private val inFoodOrLifestyle: List<String> = (food + lifestyle).distinct()

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

        val batch = feed(newPlayer(), categories = setOf(QuestionCategory.FOOD))

        assertEquals(food.sorted(), batch.ids().sorted())
        batch.forEach { question -> assertEquals(stored[question.id], question.categories, question.id) }
    }

    @Test
    fun `several categories serve each question filed under any of them once`() {
        assertTrue(food.any { it in lifestyle }, "no seed is filed under both, so a join could not repeat one")

        val batch = feed(newPlayer(), categories = foodOrLifestyle)

        assertEquals(inFoodOrLifestyle.sorted(), batch.ids().sorted(), "each once, though some are in both")
        assertTrue(batch.all { question -> question.categories.any { it in foodOrLifestyle } })
    }

    @Test
    fun `several categories are one pool that serves only what is due in it while anything is`() {
        val player = newPlayer()
        food.forEach { id -> answer(player, id) }

        val batch = feed(player, categories = foodOrLifestyle)

        assertEquals((lifestyle - food.toSet()).sorted(), batch.ids().sorted(), "the food is done, so not again yet")
        assertTrue(batch.none { it.answeredBefore })
    }

    @Test
    fun `several categories with nothing due are served again while the rest of the cycle is not done`() {
        val player = newPlayer()
        inFoodOrLifestyle.forEach { id -> answer(player, id) }

        val batch = feed(player, categories = foodOrLifestyle)

        assertEquals(inFoodOrLifestyle.sorted(), batch.ids().sorted(), "all of them again, each once")
        assertTrue(batch.all { it.answeredBefore })
        assertEquals(1, cycleOf(player), "a cycle is the player's, and the other categories are still due")
        assertEquals((pool - inFoodOrLifestyle.toSet()).sorted(), feed(player).ids().sorted())
    }

    @Test
    fun `the due count in several categories counts each question due in any of them once`() {
        val player = newPlayer()
        answer(player, inFoodOrLifestyle.first())

        assertEquals(inFoodOrLifestyle.size - 1L, dueCount(player, foodOrLifestyle))
        assertEquals(food.size - 1L, dueCount(player, setOf(QuestionCategory.FOOD)))
        assertEquals(pool.size - 1L, dueCount(player, categories = emptySet()), "every category for none")
    }

    @Test
    fun `a batch reads its categories in one statement however many questions it holds`() {
        val (one, all) = newPlayer() to newPlayer()

        val statements =
            listOf(1 to one, pool.size to all).map { (limit, player) ->
                transaction(database) {
                    val served = QuestionStore.feed(player, limit, categories = emptySet()).questions.size
                    assertEquals(limit, served)
                    statementCount
                }
            }

        assertEquals(statements.first(), statements.last(), "one statement per question would grow with the batch")
    }

    @Test
    fun `a stored name this build does not know reads as RANDOM and each category goes out once`() {
        val author = newPlayer()
        // As a newer build could have filed them, read back by this one after a rollback. The key
        // keeps a name to one row, so only two names that both read as RANDOM can repeat one.
        val beside = storedUnder(author, QuestionCategory.FOOD.name, "FROM_THE_FUTURE")
        val merged = storedUnder(author, QuestionCategory.RANDOM.name, "FROM_THE_FUTURE", "ANOTHER_ONE")
        val expected =
            mapOf(
                beside to listOf(QuestionCategory.FOOD, QuestionCategory.RANDOM),
                merged to listOf(QuestionCategory.RANDOM),
            )

        val served = feed(newPlayer()).filter { it.id in expected }.associate { it.id to it.categories }
        val listed = transaction(database) { SubmissionStore.byAuthor(author) }.associate { it.id to it.categories }

        assertEquals(expected, served, "the feed")
        assertEquals(expected, listed, "the author's list")
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
                { QuestionStore.feed(player, WyrApi.Limits.MAX_PAGE_SIZE, categories = emptySet()).questions },
                { QuestionStore.feed(player, WyrApi.Limits.MAX_PAGE_SIZE, categories = emptySet()).questions },
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

        val batch = feed(player, categories = setOf(QuestionCategory.FOOD))

        assertEquals(food.sorted(), batch.ids().sorted(), "all of that category again, rather than nothing")
        assertTrue(batch.all { it.answeredBefore })
        assertEquals(1, cycleOf(player), "a cycle is the player's, and the other categories are still due")
        assertEquals((pool - food.toSet()).sorted(), feed(player).ids().sorted(), "so unfiltered only they are due")
    }

    @Test
    fun `the next cycle starts once nothing at all is due, whichever category is asked for`() {
        val player = newPlayer()
        pool.forEach { id -> answer(player, id) }

        val batch = feed(player, categories = setOf(QuestionCategory.FOOD))

        assertEquals(food.sorted(), batch.ids().sorted())
        assertEquals(2, cycleOf(player))
        assertEquals(pool.sorted(), feed(player).ids().sorted(), "every category is due in the new cycle")
    }

    @Test
    fun `a request that finds no questions at all starts no cycle`() {
        val player = newPlayer()
        pool.forEach { id -> answer(player, id) }

        // UNKNOWN is never stored, so it stands for a category with no questions in it.
        assertTrue(feed(player, categories = setOf(QuestionCategory.UNKNOWN)).isEmpty())

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

    /** An approved question by [author], filed under the stored [names] as they are, known or not. */
    private fun storedUnder(
        author: String,
        vararg names: String,
    ): String {
        val id = UUID.randomUUID().toString()
        transaction(database) {
            Questions.insert { row ->
                row[Questions.id] = id
                row[optionA] = "A of $id"
                row[optionB] = "B of $id"
                row[authorPlayerId] = author
                row[status] = QuestionStatus.APPROVED
                row[submittedAt] = 1_000L
                row[reviewedAt] = 2_000L
                row[rejectionReason] = null
            }
            QuestionCategories.batchInsert(names.toList()) { name ->
                this[QuestionCategories.questionId] = id
                this[QuestionCategories.category] = name
            }
        }
        return id
    }

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
        categories: Set<QuestionCategory> = emptySet(),
    ): List<QuestionDto> = transaction(database) { QuestionStore.feed(player, limit, categories).questions }

    private fun cycleOf(player: String): Int? = transaction(database) { PlayerStore.find(player)?.cycle }

    /** [QuestionStore.dueCount] read as `StatsStore` reads it, against the player's own cycle. */
    private fun dueCount(
        player: String,
        categories: Set<QuestionCategory>,
    ): Long? =
        transaction(database) {
            val due = QuestionStore.dueCount(player, categories, cycle = Players.currentCycle)
            Players.select(due).where { Players.id eq player }.single()[due]
        }

    private fun List<QuestionDto>.ids(): List<String> = map { it.id }
}

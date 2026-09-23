package io.ntole.wyr.server.question

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.vote.VoteStore
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The feed's order, with answer times chosen here. Over HTTP several answers can land in one
 * millisecond, and ties are broken at random, so this is where the exact order is pinned.
 */
class QuestionStoreTest {
    private val database =
        connectH2(h2Url("wyr-question-store-${UUID.randomUUID()}"), Connection.TRANSACTION_READ_COMMITTED)

    private val pool: List<String> =
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.questionsIfEmpty()
            Questions.select(Questions.id).map { it[Questions.id] }
        }

    @Test
    fun `answered questions loop back least recently answered first`() {
        val player = newPlayer()
        // Times in an order unrelated to the pool's own, so only sorting by them can reproduce it.
        val answeredAt = pool.shuffled(Random(seed = 7)).withIndex().associate { (index, id) -> id to 1_000L + index }
        answeredAt.forEach { (id, time) -> answer(player, id, at = time) }
        val oldestFirst = answeredAt.entries.sortedBy { it.value }.map { it.key }

        assertEquals(oldestFirst, feed(player).map { it.id })
        assertEquals(oldestFirst.take(3), feed(player, limit = 3).map { it.id }, "a short batch takes the oldest")
        assertTrue(feed(player).all { it.answeredBefore })
    }

    @Test
    fun `every unanswered question comes before any answered one however old`() {
        val player = newPlayer()
        val (unanswered, answered) = pool.take(2) to pool.drop(2)
        // The oldest answer of all goes to the question the loop must still serve last.
        answer(player, answered.first(), at = 1L)
        answered.drop(1).forEachIndexed { index, id -> answer(player, id, at = 1_000L + index) }

        val batch = feed(player)

        assertEquals(unanswered.toSet(), batch.take(2).map { it.id }.toSet())
        assertTrue(batch.take(2).none { it.answeredBefore })
        assertEquals(answered, batch.drop(2).map { it.id })
    }

    @Test
    fun `another player's answers do not count as this player's`() {
        val player = newPlayer()
        val other = newPlayer()
        pool.forEachIndexed { index, id -> answer(other, id, at = 1_000L + index) }

        val batch = feed(player)

        assertEquals(pool.toSet(), batch.map { it.id }.toSet())
        assertEquals(pool.size, batch.size, "one row per question, whoever else answered it")
        assertFalse(batch.any { it.answeredBefore })
    }

    private fun newPlayer(): String =
        transaction(database) { PlayerStore.createGuest(UUID.randomUUID().toString(), Long.MAX_VALUE).id }

    private fun answer(
        player: String,
        questionId: String,
        at: Long,
    ) {
        transaction(database) { VoteStore.cast(player, questionId, OptionSide.A, now = at) }
    }

    private fun feed(
        player: String,
        limit: Int = WyrApi.Limits.MAX_PAGE_SIZE,
    ): List<QuestionDto> = transaction(database) { QuestionStore.feed(player, limit, category = null).questions }
}

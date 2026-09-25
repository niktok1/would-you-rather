package io.ntole.wyr.server.question

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.INSERTING_INTO_SKIPS
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.Skips
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.filedUnder
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.player.StatsStore
import io.ntole.wyr.server.vote.Scoring
import io.ntole.wyr.server.vote.VoteStore
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Skips and the feed's cycles, driven through the stores at READ COMMITTED as in QuestionStoreTest,
 * so a player's cycle and skips can be read directly and a race between two skips staged.
 */
class SkipStoreTest {
    private val url = h2Url("wyr-skip-store-${UUID.randomUUID()}")
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

    private val ethics: List<String> = transaction(database) { filedUnder(QuestionCategory.ETHICS) }

    @Test
    fun `a skipped question is not due for the rest of the cycle`() {
        val player = newPlayer()

        skip(player, QUESTION)

        assertEquals((pool - QUESTION).sorted(), feed(player).ids().sorted(), "no batch serves it again this cycle")
        assertEquals(pool.size - 1, statsOf(player).dueThisCycle, "and the stats, on the feed's predicate, agree")
        assertEquals(1, cycleOf(player))
    }

    @Test
    fun `a cycle with nothing left but skipped questions is finished and the next one serves them again`() {
        val player = newPlayer()
        val (skipped, answered) = pool.take(3) to pool.drop(3)
        answered.forEach { id -> answer(player, id) }
        skipped.forEach { id -> skip(player, id) }
        assertEquals(0, statsOf(player).dueThisCycle, "nothing is due, so the cycle is finished")
        assertEquals(1, cycleOf(player), "though the next one starts only on the next feed request")

        val next = feed(player)

        assertEquals(2, cycleOf(player))
        assertEquals(pool.sorted(), next.ids().sorted(), "the skipped questions come back with the rest")
        assertEquals(skipped.sorted(), next.filterNot { it.answeredBefore }.ids().sorted(), "a skip is no answer")
        assertEquals(pool.size, statsOf(player).dueThisCycle)
    }

    @Test
    fun `a question skipped in one cycle and again in the next is out of that one too`() {
        val player = newPlayer()
        skip(player, QUESTION)
        (pool - QUESTION).forEach { id -> answer(player, id) }
        assertEquals(pool.sorted(), feed(player).ids().sorted(), "due again in cycle 2")

        skip(player, QUESTION)

        assertEquals((pool - QUESTION).sorted(), feed(player).ids().sorted())
        assertEquals(listOf(QUESTION to 2), skipsOf(player), "one skip kept, the latest")
    }

    @Test
    fun `skipping one question again in a later cycle leaves the player's other skips where they were`() {
        val player = newPlayer()
        val other = pool.first { it != QUESTION }
        skip(player, QUESTION)
        skip(player, other)
        (pool - QUESTION - other).forEach { id -> answer(player, id) }
        assertEquals(pool.sorted(), feed(player).ids().sorted(), "both due again in cycle 2")

        skip(player, QUESTION)

        assertEquals(setOf(QUESTION to 2, other to 1), skipsOf(player).toSet(), "only that one skip moved")
        assertEquals((pool - QUESTION).sorted(), feed(player).ids().sorted(), "so the other is still due in cycle 2")
    }

    @Test
    fun `skipping again in the same cycle changes nothing`() {
        val player = newPlayer()
        skip(player, QUESTION)

        skip(player, QUESTION)

        assertEquals(listOf(QUESTION to 1), skipsOf(player))
        assertEquals(pool.size - 1, statsOf(player).dueThisCycle)
    }

    @Test
    fun `a skip pays nothing and changes no vote, no count and no tally`() {
        val player = newPlayer()
        val (answered, unanswered) = pool[0] to pool[1]
        answer(newPlayer(), answered)
        answer(player, answered)
        val before = statsOf(player)

        skip(player, answered)
        skip(player, unanswered)

        assertEquals(
            before.copy(dueThisCycle = before.dueThisCycle - 1),
            statsOf(player),
            "only the unanswered one stops being due; the answered one already was not",
        )
        assertEquals(VoteTallyDto(votesA = 3, votesB = 0), answer(newPlayer(), answered).tally, "two votes, no skip")
        (pool - answered - unanswered).forEach { id -> answer(player, id) }
        val nextCycle = feed(player).associateBy { it.id }
        assertEquals(true, nextCycle[answered]?.answeredBefore, "its vote is still the player's")
        assertEquals(false, nextCycle[unanswered]?.answeredBefore)
    }

    @Test
    fun `an answer after a skip in the same cycle is an ordinary answer`() {
        val player = newPlayer()
        skip(player, QUESTION)

        val answered = answer(player, QUESTION)

        assertEquals(Scoring.POINTS_PER_ANSWER, answered.pointsAwarded)
        assertEquals(false, answered.replayed)
        assertEquals(VoteTallyDto(votesA = 1, votesB = 0), answered.tally)
        val stats = statsOf(player)
        assertEquals(1, stats.answersGiven)
        assertEquals(1, stats.questionsAnswered)
        assertEquals(pool.size - 1, stats.dueThisCycle, "done for this cycle, and counted once")
    }

    @Test
    fun `categories with nothing due but a skip are served again while the rest of the cycle is not done`() {
        // Provisional (CLAUDE.md §8b): the Categories rule as written, pending the user's decision.
        val player = newPlayer()
        val chosen = (food + ethics).distinct()
        val skipped = chosen.first()
        chosen.drop(1).forEach { id -> answer(player, id) }
        skip(player, skipped)

        val batch = feed(player, categories = setOf(QuestionCategory.FOOD, QuestionCategory.ETHICS))

        assertEquals(chosen.sorted(), batch.ids().sorted(), "all of those categories again, as when all is answered")
        assertEquals(listOf(skipped), batch.filterNot { it.answeredBefore }.ids())
        assertEquals(1, cycleOf(player), "a cycle is the player's, and the other categories are still due")
    }

    @Test
    fun `another player's skips do not count as this player's`() {
        val player = newPlayer()
        val skippers = List(2) { newPlayer() }
        skippers.forEach { skipper -> skip(skipper, QUESTION) }

        assertEquals(pool.sorted(), feed(player).ids().sorted(), "the whole pool, each question once")
        assertEquals(pool.size, statsOf(player).dueThisCycle)
        assertEquals(pool.size - 1, statsOf(skippers.first()).dueThisCycle, "while each skip counts for its player")
    }

    @Test
    fun `two first skips racing on one question leave one skip`() {
        val player = newPlayer()

        // The second finds no skip either, inserts, waits on the first's key and fails on it once
        // the first commits. Only Exposed rerunning its whole transaction turns it into a repeat.
        raceBehindFirst(
            url,
            database,
            { SkipStore.skip(player, QUESTION) },
            { SkipStore.skip(player, QUESTION) },
            queued = INSERTING_INTO_SKIPS,
        )

        assertEquals(listOf(QUESTION to 1), skipsOf(player))
    }

    @Test
    fun `a skip that waited for another skip of the question counts for the cycle that started meanwhile`() {
        val player = newPlayer()
        skip(player, QUESTION)
        (pool - QUESTION).forEach { id -> answer(player, id) }

        // The first holds the skip's lock, as a skip of the same question still in flight would, and
        // the skip queues on it. Meanwhile the feed finds nothing due and starts cycle 2.
        raceBehindFirst(
            url,
            database,
            { lockSkip(player) },
            { SkipStore.skip(player, QUESTION) },
            whileQueued = { transaction(database) { QuestionStore.feed(player, limit = 1, categories = emptySet()) } },
        )

        assertEquals(2, cycleOf(player))
        assertEquals((pool - QUESTION).sorted(), feed(player).ids().sorted(), "skipped in cycle 2, so not due in it")
    }

    private fun newPlayer(): String =
        transaction(database) { PlayerStore.createGuest(UUID.randomUUID().toString(), Long.MAX_VALUE).id }

    private fun skip(
        player: String,
        questionId: String,
    ) = transaction(database) { SkipStore.skip(player, questionId) }

    private fun answer(
        player: String,
        questionId: String,
    ): VoteResultDto =
        transaction(database) {
            VoteStore.cast(player, questionId, OptionSide.A, attemptId = UUID.randomUUID().toString())
        }

    private fun feed(
        player: String,
        categories: Set<QuestionCategory> = emptySet(),
    ): List<QuestionDto> =
        transaction(database) { QuestionStore.feed(player, WyrApi.Limits.MAX_PAGE_SIZE, categories).questions }

    private fun statsOf(player: String): PlayerStatsDto =
        checkNotNull(transaction(database) { StatsStore.of(player) }) { "player $player has no stats" }

    private fun cycleOf(player: String): Int? = transaction(database) { PlayerStore.find(player)?.cycle }

    /** Every skip the player has, as the question and the cycle it was skipped in. */
    private fun skipsOf(player: String): List<Pair<String, Int>> =
        transaction(database) {
            Skips
                .selectAll()
                .where { Skips.playerId eq player }
                .map { it[Skips.questionId] to it[Skips.skippedInCycle] }
        }

    /** Takes the player's skip of [QUESTION] as `SkipStore.skip` does, to hold it until the transaction ends. */
    private fun lockSkip(player: String): ResultRow =
        Skips
            .selectAll()
            .where { (Skips.playerId eq player) and (Skips.questionId eq QUESTION) }
            .forUpdate()
            .single()

    private fun List<QuestionDto>.ids(): List<String> = map { it.id }

    private companion object {
        const val QUESTION = "seed-1"
    }
}

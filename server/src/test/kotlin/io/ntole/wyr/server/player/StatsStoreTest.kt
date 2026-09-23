package io.ntole.wyr.server.player

import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
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

/** The stats read at the server's READ COMMITTED, set by hand as in VoteStoreTest. */
class StatsStoreTest {
    private val url = h2Url("wyr-stats-store-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    private val pool: List<String> =
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.questionsIfEmpty()
            Questions.select(Questions.id).map { it[Questions.id] }
        }

    @Test
    fun `an answer committed while the stats are read shows in all of them or in none`() {
        val player = newPlayer()
        val elsewhere = Executors.newSingleThreadExecutor()
        try {
            val stats =
                transaction(database) {
                    // Commits an answer right after this transaction's first statement. Read a number
                    // per statement, the ones after it would count that answer and the ones before not.
                    registerInterceptor(
                        afterFirstStatement {
                            elsewhere
                                .submit(Callable { answer(player, pool.first()) })
                                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        },
                    )
                    StatsStore.of(player)
                }

            assertEquals(fresh(player), stats, "all from before the answer")
            assertEquals(1, statsOf(player)?.answersGiven, "and the answer did land")
        } finally {
            elsewhere.shutdownNow()
        }
    }

    @Test
    fun `answers given are counted apart from points`() {
        val player = newPlayer()
        // What a like will do: pay the player a point with no answer behind it.
        transaction(database) { PlayerStore.addPoints(player, points = 5) }

        val stats = statsOf(player)

        assertEquals(5, stats?.totalPoints)
        assertEquals(0, stats?.answersGiven)
    }

    @Test
    fun `another player's answers are not counted as this player's`() {
        val player = newPlayer()
        val other = newPlayer()
        pool.forEach { id -> answer(other, id) }

        assertEquals(fresh(player), statsOf(player))
    }

    @Test
    fun `a player that does not exist has no stats`() {
        assertNull(statsOf("no-such-player"))
    }

    private fun fresh(player: String) =
        PlayerStatsDto(
            playerId = player,
            totalPoints = 0,
            answersGiven = 0,
            questionsAnswered = 0,
            cycle = Players.FIRST_CYCLE,
            dueThisCycle = pool.size,
        )

    private fun newPlayer(): String =
        transaction(database) { PlayerStore.createGuest(UUID.randomUUID().toString(), Long.MAX_VALUE).id }

    private fun answer(
        player: String,
        questionId: String,
    ): VoteResultDto =
        transaction(database) {
            VoteStore.cast(player, questionId, OptionSide.A, attemptId = UUID.randomUUID().toString())
        }

    private fun statsOf(player: String): PlayerStatsDto? = transaction(database) { StatsStore.of(player) }

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
        const val TIMEOUT_SECONDS = 10L
    }
}

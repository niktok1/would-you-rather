package io.ntole.wyr.server.vote

import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.INSERTING_INTO_VOTES
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.PlayerStore
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The vote store at the server's READ COMMITTED, set by hand as in PlayerStoreTest, so the races
 * stay discriminating whatever the server's level becomes.
 */
class VoteStoreTest {
    private val url = h2Url("wyr-vote-store-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.questionsIfEmpty()
        }
    }

    @Test
    fun `answering again moves the one vote and keeps when it was first cast`() {
        val player = newPlayer()
        cast(player, OptionSide.A, at = 1_000L)

        val again = cast(player, OptionSide.B, at = 2_000L)

        assertEquals(VoteTallyDto(votesA = 0, votesB = 1), again.tally)
        assertEquals(2 * Scoring.POINTS_PER_ANSWER, again.totalPoints)
        val vote = storedVotes().single()
        assertEquals(OptionSide.B.name, vote[Votes.side])
        assertEquals(1_000L, vote[Votes.createdAt], "created_at is the first answer")
        assertEquals(2_000L, vote[Votes.answeredAt], "answered_at is the latest")
    }

    @Test
    fun `two first answers racing on one question are both paid and leave one vote`() {
        val player = newPlayer()

        // The second inserts too, waits on the first's key, fails on it once the first commits,
        // and only Exposed rerunning its whole transaction turns it into a re-answer.
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { VoteStore.cast(player, QUESTION, OptionSide.A) },
                { VoteStore.cast(player, QUESTION, OptionSide.B) },
                queued = INSERTING_INTO_VOTES,
            )

        assertEquals(Scoring.POINTS_PER_ANSWER, first.totalPoints)
        assertEquals(2 * Scoring.POINTS_PER_ANSWER, second.totalPoints)
        assertEquals(VoteTallyDto(votesA = 0, votesB = 1), second.tally)
        assertEquals(OptionSide.B.name, storedVotes().single()[Votes.side])
    }

    @Test
    fun `another player switching sides while the tally is counted is counted once`() {
        val switcher = newPlayer()
        val caster = newPlayer()
        cast(switcher, OptionSide.A)
        val elsewhere = Executors.newSingleThreadExecutor()
        try {
            val result =
                transaction(database) {
                    // Commits the switch right after this transaction's first count. Counted a side
                    // per statement, the second count would see the switcher's vote again.
                    registerInterceptor(
                        afterFirstCount {
                            elsewhere
                                .submit(Callable { switch(switcher) })
                                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        },
                    )
                    VoteStore.cast(caster, QUESTION, OptionSide.B)
                }

            assertEquals(2L, result.tally.votesA + result.tally.votesB, "two players hold two votes")
            assertEquals(OptionSide.B.name, storedVotes().first { it[Votes.playerId] == switcher }[Votes.side])
        } finally {
            elsewhere.shutdownNow()
        }
    }

    private fun newPlayer(): String =
        transaction(database) { PlayerStore.createGuest(UUID.randomUUID().toString(), Long.MAX_VALUE).id }

    private fun cast(
        player: String,
        choice: OptionSide,
        at: Long = System.currentTimeMillis(),
    ): VoteResultDto = transaction(database) { VoteStore.cast(player, QUESTION, choice, now = at) }

    private fun switch(player: String): VoteResultDto = cast(player, OptionSide.B)

    private fun storedVotes(): List<ResultRow> =
        transaction(database) { Votes.selectAll().where { Votes.questionId eq QUESTION }.toList() }

    /** Runs [action] once, after the first statement of the transaction that counts anything. */
    private fun afterFirstCount(action: () -> Unit): StatementInterceptor =
        object : StatementInterceptor {
            private var fired = false

            override fun afterExecution(
                transaction: Transaction,
                contexts: List<StatementContext>,
                executedStatement: PreparedStatementApi,
            ) {
                if (fired || contexts.none { "COUNT(" in it.sql(transaction).uppercase() }) return
                fired = true
                action()
            }
        }

    private companion object {
        const val QUESTION = "seed-1"
        const val TIMEOUT_SECONDS = 10L
    }
}

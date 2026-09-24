package io.ntole.wyr.server.vote

import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.INSERTING_INTO_VOTES
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.question.QuestionStore
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
        cast(player, OptionSide.A, attempt = "first", at = 1_000L)

        val again = cast(player, OptionSide.B, attempt = "second", at = 2_000L)

        assertEquals(VoteTallyDto(votesA = 0, votesB = 1), again.tally)
        assertEquals(2 * Scoring.POINTS_PER_ANSWER, again.totalPoints)
        val vote = storedVotes().single()
        assertEquals(OptionSide.B.name, vote[Votes.side])
        assertEquals(1_000L, vote[Votes.createdAt], "created_at is the first answer")
        assertEquals(2_000L, vote[Votes.answeredAt], "answered_at is the latest")
        assertEquals("second", vote[Votes.attemptId], "the attempt kept is the latest")
    }

    @Test
    fun `a replay writes nothing`() {
        val player = newPlayer()
        cast(player, OptionSide.A, attempt = "only", at = 1_000L)

        val replay = cast(player, OptionSide.B, attempt = "only", at = 2_000L)

        assertEquals(true, replay.replayed)
        assertEquals(Scoring.POINTS_PER_ANSWER, replay.totalPoints)
        val vote = storedVotes().single()
        assertEquals(OptionSide.A.name, vote[Votes.side])
        assertEquals(1_000L, vote[Votes.answeredAt], "a replay is not an answer")
    }

    @Test
    fun `every paid answer is counted as given and a replay is not`() {
        val player = newPlayer()
        cast(player, OptionSide.A, attempt = "first")

        cast(player, OptionSide.B, attempt = "second")
        val replay = cast(player, OptionSide.B, attempt = "second")

        assertEquals(true, replay.replayed)
        assertEquals(2, answersGivenBy(player), "the first answer and the re-answer, not the replay")
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
                { VoteStore.cast(player, QUESTION, OptionSide.A, attemptId = "first") },
                { VoteStore.cast(player, QUESTION, OptionSide.B, attemptId = "second") },
                queued = INSERTING_INTO_VOTES,
            )

        assertEquals(Scoring.POINTS_PER_ANSWER, first.totalPoints)
        assertEquals(2 * Scoring.POINTS_PER_ANSWER, second.totalPoints)
        assertEquals(false, second.replayed)
        assertEquals(VoteTallyDto(votesA = 0, votesB = 1), second.tally)
        assertEquals(OptionSide.B.name, storedVotes().single()[Votes.side])
    }

    @Test
    fun `a retry racing its own first answer is replayed`() {
        val player = newPlayer()

        // The client gave up waiting and resent the same attempt while the first was in flight.
        val (first, retry) =
            raceBehindFirst(
                url,
                database,
                { VoteStore.cast(player, QUESTION, OptionSide.A, attemptId = "only") },
                { VoteStore.cast(player, QUESTION, OptionSide.A, attemptId = "only") },
                queued = INSERTING_INTO_VOTES,
            )

        assertEquals(false, first.replayed)
        assertEquals(true, retry.replayed)
        assertEquals(0, retry.pointsAwarded)
        assertEquals(Scoring.POINTS_PER_ANSWER, retry.totalPoints, "the retry paid nothing and saw the first's point")
    }

    @Test
    fun `a retry racing its own re-answer waits for it and is replayed`() {
        val player = newPlayer()
        cast(player, OptionSide.A, attempt = "earlier")

        // The retry reads the vote while the re-answer holds it uncommitted. Read without a lock it
        // would still show the earlier attempt, and the retry would pay as a fresh answer.
        val (reAnswer, retry) =
            raceBehindFirst(
                url,
                database,
                { VoteStore.cast(player, QUESTION, OptionSide.B, attemptId = "later") },
                { VoteStore.cast(player, QUESTION, OptionSide.B, attemptId = "later") },
            )

        assertEquals(false, reAnswer.replayed)
        assertEquals(true, retry.replayed)
        assertEquals(OptionSide.B, retry.yourChoice)
        assertEquals(2 * Scoring.POINTS_PER_ANSWER, retry.totalPoints, "only the re-answer paid, and the replay saw it")
    }

    @Test
    fun `an answer that waited for the vote counts for the cycle that started meanwhile`() {
        val player = newPlayer()
        val pool = transaction(database) { Questions.select(Questions.id).map { it[Questions.id] } }
        pool.forEach { id -> transaction(database) { VoteStore.cast(player, id, OptionSide.A, attemptId = "cycle-1") } }

        // The first holds the vote's lock, as an answer to the same question still in flight would,
        // and the answer queues on it. Meanwhile the feed finds nothing due and starts cycle 2.
        raceBehindFirst(
            url,
            database,
            { lockVote(player) },
            { VoteStore.cast(player, QUESTION, OptionSide.B, attemptId = "late") },
            whileQueued = { transaction(database) { QuestionStore.feed(player, limit = 1, categories = emptySet()) } },
        )

        val cycle2 = transaction(database) { QuestionStore.feed(player, pool.size, categories = emptySet()).questions }
        assertEquals(pool.size - 1, cycle2.size, "the answer landed in cycle 2, so its question is not due in it")
        assertFalse(cycle2.any { it.id == QUESTION })
    }

    @Test
    fun `another player switching sides while the tally is counted is counted once`() {
        val switcher = newPlayer()
        val caster = newPlayer()
        cast(switcher, OptionSide.A, attempt = "before")
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
                    VoteStore.cast(caster, QUESTION, OptionSide.B, attemptId = "caster")
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
        attempt: String,
        at: Long = System.currentTimeMillis(),
    ): VoteResultDto = transaction(database) { VoteStore.cast(player, QUESTION, choice, attempt, now = at) }

    private fun switch(player: String): VoteResultDto = cast(player, OptionSide.B, attempt = "after")

    private fun storedVotes(): List<ResultRow> =
        transaction(database) { Votes.selectAll().where { Votes.questionId eq QUESTION }.toList() }

    private fun answersGivenBy(player: String): Int =
        transaction(database) {
            Players.select(Players.answersGiven).where { Players.id eq player }.single()[Players.answersGiven]
        }

    /** Takes the player's vote on [QUESTION] as `VoteStore.cast` does, to hold it until the transaction ends. */
    private fun lockVote(player: String): ResultRow =
        Votes
            .selectAll()
            .where { (Votes.playerId eq player) and (Votes.questionId eq QUESTION) }
            .forUpdate()
            .single()

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

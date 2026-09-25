package io.ntole.wyr.server.player

import io.ntole.wyr.core.like.LikeResultDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.server.auth.AccountStore
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.like.LikeStore
import io.ntole.wyr.server.moderation.ModerationStore
import io.ntole.wyr.server.question.SubmissionStore
import io.ntole.wyr.server.vote.Scoring
import io.ntole.wyr.server.vote.VoteStore
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
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
    fun `a like committed while the stats are read shows in the points and the likes received or in neither`() {
        val author = newPlayer()
        val question = approvedBy(author)
        val fan = newPlayer()
        val elsewhere = Executors.newSingleThreadExecutor()
        try {
            val stats =
                transaction(database) {
                    // Commits a like of the author's question right after this transaction's first
                    // statement. Read apart from the total, the likes received would count it and the
                    // total would not hold its point.
                    registerInterceptor(
                        afterFirstStatement {
                            elsewhere
                                .submit(Callable { like(fan, question) })
                                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        },
                    )
                    StatsStore.of(author)
                }

            assertEquals(fresh(author).copy(dueThisCycle = pool.size + 1), stats, "all from before the like")
            val liked = statsOf(author)
            assertEquals(Scoring.POINTS_PER_LIKE, liked?.totalPoints, "and the like did land")
            assertEquals(1, liked?.likesReceived)
        } finally {
            elsewhere.shutdownNow()
        }
    }

    @Test
    fun `a submission committed while the stats are read shows in the points and the points spent or in neither`() {
        val author = newPlayer()
        transaction(database) { PlayerStore.addPoints(author, points = Scoring.SUBMISSION_COST) }
        val elsewhere = Executors.newSingleThreadExecutor()
        try {
            val stats =
                transaction(database) {
                    // Commits a submission right after this transaction's first statement. Read apart from
                    // the total, the points spent would count its cost and the total would still hold it.
                    registerInterceptor(
                        afterFirstStatement {
                            elsewhere
                                .submit(Callable { submit(author) })
                                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        },
                    )
                    StatsStore.of(author)
                }

            assertEquals(fresh(author).copy(totalPoints = Scoring.SUBMISSION_COST), stats, "all from before it")
            val spent = statsOf(author)
            assertEquals(0, spent?.totalPoints, "and it did land")
            assertEquals(Scoring.SUBMISSION_COST, spent?.pointsSpent)
        } finally {
            elsewhere.shutdownNow()
        }
    }

    @Test
    fun `the points spent are what the questions not rejected cost and the total is what is left`() {
        val author = newPlayer()
        answer(author, pool.first())
        transaction(database) { PlayerStore.addPoints(author, points = 2 * Scoring.SUBMISSION_COST) }
        val (_, approved, rejected) = Triple(submit(author), submit(author), submit(author))
        transaction(database) { ModerationStore.approve(approved, emptyList()) }
        like(newPlayer(), approved)
        transaction(database) {
            ModerationStore.reject(rejected, "Not a dilemma")
            ModerationStore.retire(approved)
        }

        val stats = checkNotNull(statsOf(author))

        assertEquals(2 * Scoring.SUBMISSION_COST, stats.pointsSpent, "the pending one and the retired one")
        assertEquals(
            stats.answersGiven * Scoring.POINTS_PER_ANSWER + stats.likesReceived * Scoring.POINTS_PER_LIKE -
                stats.pointsSpent + 2 * Scoring.SUBMISSION_COST,
            stats.totalPoints,
            "what the answers and likes earned, less what was spent, besides the points given here",
        )
    }

    @Test
    fun `answers given are counted apart from points`() {
        val player = newPlayer()
        // What a like does: pay the player a point with no answer behind it.
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
    fun `the stats name a registered player's username and none for a guest`() {
        val (guest, registered) = newPlayer() to newPlayer()
        transaction(database) { AccountStore.register(registered, "bob", passwordHash = "hash") }

        assertEquals(fresh(guest), statsOf(guest), "a guest has none")
        assertEquals(fresh(registered).copy(username = "bob"), statsOf(registered))
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
            likesReceived = 0,
        )

    private fun newPlayer(): String = transaction(database) { PlayerStore.createGuest().id }

    private fun answer(
        player: String,
        questionId: String,
    ): VoteResultDto =
        transaction(database) {
            VoteStore.cast(player, questionId, OptionSide.A, attemptId = UUID.randomUUID().toString())
        }

    /** An approved food question by [author], as a moderator's approval would leave it. */
    private fun approvedBy(author: String): String {
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
            QuestionCategories.insert { row ->
                row[questionId] = id
                row[category] = "FOOD"
            }
        }
        return id
    }

    private fun like(
        player: String,
        questionId: String,
    ): LikeResultDto = transaction(database) { LikeStore.setLiked(player, questionId, liked = true) }

    private fun statsOf(player: String): PlayerStatsDto? = transaction(database) { StatsStore.of(player) }

    /** Submits a question as [author], who must have the points it costs, and returns its id. */
    private fun submit(author: String): String {
        val tag = UUID.randomUUID().toString().take(8)
        val request = SubmitQuestionRequest("Option $tag", "Other $tag", listOf("FOOD"))
        return transaction(database) { SubmissionStore.submit(author, request).id }
    }

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

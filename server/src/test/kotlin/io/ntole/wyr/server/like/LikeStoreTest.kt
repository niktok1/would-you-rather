package io.ntole.wyr.server.like

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.like.LikeResultDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.INSERTING_INTO_LIKES
import io.ntole.wyr.server.db.Likes
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.player.StatsStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import io.ntole.wyr.server.vote.Scoring
import io.ntole.wyr.server.vote.VoteStore
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
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
import kotlin.test.assertFailsWith

/**
 * Likes and the points they pay (CLAUDE.md §8d), driven through the stores at READ COMMITTED as in
 * SkipStoreTest, so an author's total can be read directly and two likes racing staged. Each
 * question a player submitted is written straight into the table as a moderator's decision would
 * leave it, as in ServableQuestionsTest.
 */
class LikeStoreTest {
    private val url = h2Url("wyr-like-store-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.questionsIfEmpty()
        }
    }

    @Test
    fun `a like pays the author a point and an unlike takes it back`() {
        val (author, liker) = newPlayer() to newPlayer()
        val question = submitted(author)

        assertEquals(LikeResultDto(question, likeCount = 1, likedByMe = true), like(liker, question))
        assertEquals(Scoring.POINTS_PER_LIKE, pointsOf(author))
        assertEquals(0, pointsOf(liker), "a like pays the author, not the liker")

        assertEquals(LikeResultDto(question, likeCount = 0, likedByMe = false), unlike(liker, question))
        assertEquals(0, pointsOf(author), "the point went with the like")
        assertEquals(emptyList(), likersOf(question))
    }

    @Test
    fun `liking again or unliking what is not liked changes nothing and pays nothing`() {
        val (author, liker) = newPlayer() to newPlayer()
        val question = submitted(author)

        assertEquals(LikeResultDto(question, likeCount = 0, likedByMe = false), unlike(liker, question))
        assertEquals(0, pointsOf(author), "nothing was liked, so nothing is taken back")

        like(liker, question)
        assertEquals(LikeResultDto(question, likeCount = 1, likedByMe = true), like(liker, question), "a repeat")
        assertEquals(Scoring.POINTS_PER_LIKE, pointsOf(author), "paid once")
        assertEquals(listOf(liker), likersOf(question), "and held once")

        unlike(liker, question)
        assertEquals(LikeResultDto(question, likeCount = 0, likedByMe = false), unlike(liker, question), "a repeat")
        assertEquals(0, pointsOf(author), "taken back once")
    }

    @Test
    fun `every player's like counts and pays the author and only the player's own is theirs`() {
        val author = newPlayer()
        val question = submitted(author)
        val likers = List(3) { newPlayer() }

        likers.forEach { liker -> like(liker, question) }

        assertEquals(3 * Scoring.POINTS_PER_LIKE, pointsOf(author))
        assertEquals(
            LikeResultDto(question, likeCount = 3, likedByMe = false),
            unlike(author, question),
            "the author holds none of them, so unliking changes nothing",
        )
        assertEquals(LikeResultDto(question, likeCount = 2, likedByMe = false), unlike(likers.first(), question))
        assertEquals(2 * Scoring.POINTS_PER_LIKE, pointsOf(author))
    }

    @Test
    fun `a player liking their own question is paid for it as for anyone's like`() {
        val author = newPlayer()
        val own = submitted(author)

        assertEquals(LikeResultDto(own, likeCount = 1, likedByMe = true), like(author, own))
        assertEquals(Scoring.POINTS_PER_LIKE, pointsOf(author))

        unlike(author, own)
        assertEquals(0, pointsOf(author))
    }

    @Test
    fun `a like on a seed counts and pays nobody`() {
        val (liker, other) = newPlayer() to newPlayer()

        assertEquals(LikeResultDto(SEED, likeCount = 1, likedByMe = true), like(liker, SEED))
        assertEquals(LikeResultDto(SEED, likeCount = 2, likedByMe = true), like(other, SEED))
        assertEquals(0L, everyonesPoints(), "a seed has no author")

        assertEquals(LikeResultDto(SEED, likeCount = 1, likedByMe = false), unlike(liker, SEED))
        assertEquals(0L, everyonesPoints(), "nor does an unlike take anything from anybody")
    }

    @Test
    fun `a like is no answer and changes nothing but the likes and the author's points`() {
        val (author, liker) = newPlayer() to newPlayer()
        val question = submitted(author)
        val before = statsOf(liker)

        like(liker, question)
        like(liker, SEED)

        assertEquals(before, statsOf(liker), "no point, no answer, and nothing less due for the liker")
        assertEquals(VoteTallyDto(votesA = 1, votesB = 0), answer(newPlayer(), question).tally, "and no vote")
        assertEquals(Scoring.POINTS_PER_ANSWER, answer(liker, question).pointsAwarded, "answering it still pays")
    }

    @Test
    fun `an author's points are what their answers earned plus a point for each like their questions hold`() {
        val author = newPlayer()
        val (first, second) = submitted(author) to submitted(author)
        val others = List(3) { newPlayer() }
        answer(author, SEED)
        answer(author, first)
        answer(author, SEED, OptionSide.B)
        others.forEach { other -> like(other, first) }
        like(others.first(), second)
        like(author, second)
        unlike(others.last(), first)
        like(others.first(), first)
        unlike(author, first)

        val held = likersOf(first).size + likersOf(second).size

        assertEquals(4, held, "two of three left on the first, and the second's two")
        assertEquals(
            answersGivenBy(author) * Scoring.POINTS_PER_ANSWER + held * Scoring.POINTS_PER_LIKE,
            pointsOf(author),
        )
    }

    @Test
    fun `the feed shows each question's likes and whether the player likes it before they answer`() {
        val (liker, other) = newPlayer() to newPlayer()
        val question = submitted(newPlayer())
        like(liker, question)
        like(other, question)
        like(liker, SEED)

        val forLiker = feed(liker).associateBy { it.id }
        val forOther = feed(other).associateBy { it.id }
        val forNobody = feed(newPlayer()).associateBy { it.id }

        assertEquals(false, forLiker.getValue(question).answeredBefore, "liked, not answered")
        assertEquals(listOf(2 to true, 1 to true), listOf(question, SEED).map { forLiker.getValue(it).likes() })
        assertEquals(listOf(2 to true, 1 to false), listOf(question, SEED).map { forOther.getValue(it).likes() })
        assertEquals(listOf(2 to false, 1 to false), listOf(question, SEED).map { forNobody.getValue(it).likes() })
        val rest = forNobody.filterKeys { it != question && it != SEED }.values
        assertEquals(setOf(0 to false), rest.map { it.likes() }.toSet(), "and every other one is liked by nobody")
    }

    @Test
    fun `a batch reads its likes in one statement however many questions it holds`() {
        val pool = transaction(database) { Questions.select(Questions.id).map { it[Questions.id] } }
        repeat(2) {
            val liker = newPlayer()
            pool.forEach { id -> like(liker, id) }
        }

        val statements =
            listOf(1, pool.size).map { limit ->
                val player = newPlayer()
                transaction(database) {
                    val batch = QuestionStore.feed(player, limit, categories = emptySet()).questions
                    assertEquals(limit, batch.size)
                    assertEquals(setOf(2 to false), batch.map { it.likes() }.toSet())
                    statementCount
                }
            }

        assertEquals(statements.first(), statements.last(), "one statement per question would grow with the batch")
    }

    @Test
    fun `an unlike committed while a batch's likes are read shows in their count and the player's or in neither`() {
        val player = newPlayer()
        like(player, SEED)
        val elsewhere = Executors.newSingleThreadExecutor()
        try {
            val served =
                transaction(database) {
                    // Commits the player's unlike right after the first statement that reads a like.
                    // Read one number per statement, the second would disagree with the first.
                    registerInterceptor(
                        afterFirstStatementOn(
                            "LIKES",
                            action = {
                                elsewhere
                                    .submit(Callable { unlike(player, SEED) })
                                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            },
                        ),
                    )
                    QuestionStore.feed(player, WyrApi.Limits.MAX_PAGE_SIZE, categories = emptySet()).questions
                }

            assertEquals(1 to true, served.single { it.id == SEED }.likes(), "both from before the unlike")
            assertEquals(emptyList(), likersOf(SEED), "and the unlike did land")
        } finally {
            elsewhere.shutdownNow()
        }
    }

    @Test
    fun `a question not approved cannot be liked or unliked by anybody`() {
        val (author, player) = newPlayer() to newPlayer()

        listOf(QuestionStatus.PENDING, QuestionStatus.REJECTED).forEach { status ->
            val question = submitted(author, status)
            listOf("its author" to author, "another player" to player).forEach { (who, id) ->
                assertNotFound("$status liked by $who") { like(id, question) }
                assertNotFound("$status unliked by $who") { unlike(id, question) }
            }
        }
        assertNotFound("a question that does not exist") { like(player, "no-such-question") }

        assertEquals(0L, transaction(database) { Likes.selectAll().count() })
        assertEquals(0, pointsOf(author))
    }

    @Test
    fun `a player that does not exist cannot like`() {
        val failure = assertFailsWith<ApiFailure> { like("no-such-player", SEED) }

        assertEquals(ErrorCode.UNAUTHORIZED, failure.code)
    }

    @Test
    fun `two first likes racing on one question pay the author once`() {
        val (author, liker) = newPlayer() to newPlayer()
        val question = submitted(author)

        // The second finds no like either, inserts, waits on the first's key and fails on it once
        // the first commits. Only Exposed rerunning its whole transaction turns it into a repeat.
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { LikeStore.setLiked(liker, question, liked = true) },
                { LikeStore.setLiked(liker, question, liked = true) },
                queued = INSERTING_INTO_LIKES,
            )

        assertEquals(LikeResultDto(question, likeCount = 1, likedByMe = true), first)
        assertEquals(LikeResultDto(question, likeCount = 1, likedByMe = true), second, "the rerun found the like")
        assertEquals(listOf(liker), likersOf(question))
        assertEquals(Scoring.POINTS_PER_LIKE, pointsOf(author), "paid once")
    }

    @Test
    fun `two unlikes racing take the point back once`() {
        val (author, liker) = newPlayer() to newPlayer()
        val question = submitted(author)
        like(liker, question)

        // The second's delete waits on the row the first's holds, and then finds it gone.
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { LikeStore.setLiked(liker, question, liked = false) },
                { LikeStore.setLiked(liker, question, liked = false) },
            )

        assertEquals(LikeResultDto(question, likeCount = 0, likedByMe = false), first)
        assertEquals(LikeResultDto(question, likeCount = 0, likedByMe = false), second)
        assertEquals(0, pointsOf(author), "taken back once, not twice")
    }

    private fun newPlayer(): String =
        transaction(database) { PlayerStore.createGuest(UUID.randomUUID().toString(), Long.MAX_VALUE).id }

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
                row[rejectionReason] = "not a real dilemma".takeIf { status == QuestionStatus.REJECTED }
            }
            QuestionCategories.insert { row ->
                row[questionId] = id
                row[category] = QuestionCategory.FOOD.name
            }
        }
        return id
    }

    private fun like(
        player: String,
        questionId: String,
    ): LikeResultDto = transaction(database) { LikeStore.setLiked(player, questionId, liked = true) }

    private fun unlike(
        player: String,
        questionId: String,
    ): LikeResultDto = transaction(database) { LikeStore.setLiked(player, questionId, liked = false) }

    private fun answer(
        player: String,
        questionId: String,
        choice: OptionSide = OptionSide.A,
    ): VoteResultDto =
        transaction(database) { VoteStore.cast(player, questionId, choice, attemptId = UUID.randomUUID().toString()) }

    private fun feed(player: String): List<QuestionDto> =
        transaction(database) {
            QuestionStore.feed(player, WyrApi.Limits.MAX_PAGE_SIZE, categories = emptySet()).questions
        }

    /** A served question's like count and whether the player it was served to likes it. */
    private fun QuestionDto.likes(): Pair<Int, Boolean> = likeCount to likedByMe

    private fun statsOf(player: String): PlayerStatsDto =
        checkNotNull(transaction(database) { StatsStore.of(player) }) { "player $player has no stats" }

    private fun pointsOf(player: String): Int? = transaction(database) { PlayerStore.find(player)?.totalPoints }

    private fun answersGivenBy(player: String): Int =
        transaction(database) {
            Players.select(Players.answersGiven).where { Players.id eq player }.single()[Players.answersGiven]
        }

    /** Every player's points, added up in Kotlin rather than by the SQL the stores use. */
    private fun everyonesPoints(): Long =
        transaction(database) { Players.select(Players.totalPoints).sumOf { it[Players.totalPoints].toLong() } }

    /** The players who like [questionId], straight from the table. */
    private fun likersOf(questionId: String): List<String> =
        transaction(database) {
            Likes
                .select(Likes.playerId)
                .where { Likes.questionId eq questionId }
                .map { it[Likes.playerId] }
        }

    private fun assertNotFound(
        case: String,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<ApiFailure>(case) { block() }
        assertEquals(ErrorCode.QUESTION_NOT_FOUND, failure.code, case)
    }

    /** Runs [action] once, after the first statement of the transaction whose SQL names [table]. */
    private fun afterFirstStatementOn(
        table: String,
        action: () -> Unit,
    ): StatementInterceptor =
        object : StatementInterceptor {
            private var fired = false

            override fun afterExecution(
                transaction: Transaction,
                contexts: List<StatementContext>,
                executedStatement: PreparedStatementApi,
            ) {
                if (fired || contexts.none { table in it.sql(transaction).uppercase() }) return
                fired = true
                action()
            }
        }

    private companion object {
        const val SEED = "seed-1"
        const val TIMEOUT_SECONDS = 10L
    }
}

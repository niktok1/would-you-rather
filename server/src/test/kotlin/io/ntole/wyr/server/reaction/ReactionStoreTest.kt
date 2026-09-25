package io.ntole.wyr.server.reaction

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.core.reaction.ReactionResultDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.INSERTING_INTO_REACTIONS
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Reactions
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
 * Reactions and the points likes pay (CLAUDE.md §8d, *Reactions*; §8c), driven through the stores at
 * READ COMMITTED as in SkipStoreTest, so an author's total can be read directly and two reactions
 * racing staged. Each question a player submitted is written straight into the table as a
 * moderator's decision would leave it, as in ServableQuestionsTest.
 */
class ReactionStoreTest {
    private val url = h2Url("wyr-reaction-store-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.questionsIfEmpty()
        }
    }

    @Test
    fun `a like pays the author a point and taking it back takes the point back`() {
        val (author, liker) = newPlayer() to newPlayer()
        val question = submitted(author)

        assertEquals(result(question, likes = 1, mine = Reaction.LIKE), react(liker, question, Reaction.LIKE))
        assertEquals(Scoring.POINTS_PER_LIKE, pointsOf(author))
        assertEquals(0, pointsOf(liker), "a like pays the author, not the liker")

        assertEquals(result(question), react(liker, question, Reaction.NONE))
        assertEquals(0, pointsOf(author), "the point went with the like")
        assertEquals(emptyMap(), reactionsTo(question))
    }

    @Test
    fun `a dislike pays and costs nobody anything`() {
        val (author, critic) = newPlayer() to newPlayer()
        val question = submitted(author)

        assertEquals(result(question, dislikes = 1, mine = Reaction.DISLIKE), react(critic, question, Reaction.DISLIKE))
        assertEquals(listOf(0, 0), listOf(pointsOf(author), pointsOf(critic)))

        assertEquals(result(question), react(critic, question, Reaction.NONE))
        assertEquals(listOf(0, 0), listOf(pointsOf(author), pointsOf(critic)), "nor does taking it back")
    }

    @Test
    fun `a dislike replaces a like and takes its point back, and a like replaces a dislike and pays it`() {
        val (author, player) = newPlayer() to newPlayer()
        val question = submitted(author)
        react(player, question, Reaction.LIKE)

        assertEquals(result(question, dislikes = 1, mine = Reaction.DISLIKE), react(player, question, Reaction.DISLIKE))
        assertEquals(mapOf(player to Reaction.DISLIKE), reactionsTo(question), "one reaction held, never both")
        assertEquals(0, pointsOf(author), "the like's point went with it")

        assertEquals(result(question, likes = 1, mine = Reaction.LIKE), react(player, question, Reaction.LIKE))
        assertEquals(mapOf(player to Reaction.LIKE), reactionsTo(question))
        assertEquals(Scoring.POINTS_PER_LIKE, pointsOf(author), "and back again")
    }

    @Test
    fun `asking again for what holds changes nothing and pays nothing`() {
        val (author, player) = newPlayer() to newPlayer()
        val question = submitted(author)

        assertEquals(result(question), react(player, question, Reaction.NONE), "nothing held, nothing taken back")
        assertEquals(0, pointsOf(author))

        react(player, question, Reaction.LIKE)
        assertEquals(result(question, likes = 1, mine = Reaction.LIKE), react(player, question, Reaction.LIKE), "again")
        assertEquals(Scoring.POINTS_PER_LIKE, pointsOf(author), "paid once")

        react(player, question, Reaction.DISLIKE)
        assertEquals(
            result(question, dislikes = 1, mine = Reaction.DISLIKE),
            react(player, question, Reaction.DISLIKE),
            "again",
        )
        assertEquals(0, pointsOf(author), "taken back once")
        assertEquals(mapOf(player to Reaction.DISLIKE), reactionsTo(question), "and held once")
    }

    @Test
    fun `every player's reaction counts and only the player's own is theirs`() {
        val author = newPlayer()
        val question = submitted(author)
        val (fans, critics) = List(3) { newPlayer() } to List(2) { newPlayer() }

        fans.forEach { fan -> react(fan, question, Reaction.LIKE) }
        critics.forEach { critic -> react(critic, question, Reaction.DISLIKE) }

        assertEquals(3 * Scoring.POINTS_PER_LIKE, pointsOf(author))
        assertEquals(
            result(question, likes = 3, dislikes = 2),
            react(author, question, Reaction.NONE),
            "the author holds none of them, so taking theirs back changes nothing",
        )
        assertEquals(result(question, likes = 2, dislikes = 2), react(fans.first(), question, Reaction.NONE))
        assertEquals(result(question, likes = 2, dislikes = 1), react(critics.first(), question, Reaction.NONE))
        assertEquals(2 * Scoring.POINTS_PER_LIKE, pointsOf(author))
    }

    @Test
    fun `a player liking their own question is paid for it and disliking it costs them nothing`() {
        val author = newPlayer()
        val own = submitted(author)

        assertEquals(result(own, likes = 1, mine = Reaction.LIKE), react(author, own, Reaction.LIKE))
        assertEquals(Scoring.POINTS_PER_LIKE, pointsOf(author))

        assertEquals(result(own, dislikes = 1, mine = Reaction.DISLIKE), react(author, own, Reaction.DISLIKE))
        assertEquals(0, pointsOf(author))
    }

    @Test
    fun `a reaction to a seed counts and pays nobody`() {
        val (fan, critic) = newPlayer() to newPlayer()

        assertEquals(result(SEED, likes = 1, mine = Reaction.LIKE), react(fan, SEED, Reaction.LIKE))
        assertEquals(
            result(SEED, likes = 1, dislikes = 1, mine = Reaction.DISLIKE),
            react(critic, SEED, Reaction.DISLIKE),
        )
        assertEquals(0L, everyonesPoints(), "a seed has no author")

        assertEquals(result(SEED, dislikes = 2, mine = Reaction.DISLIKE), react(fan, SEED, Reaction.DISLIKE))
        assertEquals(0L, everyonesPoints(), "nor does a change of mind take anything from anybody")
    }

    @Test
    fun `a reaction is no answer and changes nothing but the reactions and the author's points`() {
        val (author, player) = newPlayer() to newPlayer()
        val question = submitted(author)
        val before = statsOf(player)

        react(player, question, Reaction.LIKE)
        react(player, SEED, Reaction.DISLIKE)

        assertEquals(before, statsOf(player), "no point, no answer, and nothing less due for the player")
        assertEquals(VoteTallyDto(votesA = 1, votesB = 0), answer(newPlayer(), question).tally, "and no vote")
        assertEquals(Scoring.POINTS_PER_ANSWER, answer(player, question).pointsAwarded, "answering it still pays")
    }

    @Test
    fun `an author's points are what their answers earned plus a point for each like their questions hold`() {
        val author = newPlayer()
        val (first, second) = submitted(author) to submitted(author)
        val others = List(3) { newPlayer() }
        answer(author, SEED)
        answer(author, first)
        answer(author, SEED, OptionSide.B)
        others.forEach { other -> react(other, first, Reaction.LIKE) }
        react(others.first(), second, Reaction.LIKE)
        react(author, second, Reaction.LIKE)
        react(others.last(), first, Reaction.DISLIKE)
        react(others[1], second, Reaction.DISLIKE)
        react(author, first, Reaction.DISLIKE)

        val held = likersOf(first).size + likersOf(second).size

        assertEquals(4, held, "two of three left on the first, and the second's two")
        assertEquals(
            answersGivenBy(author) * Scoring.POINTS_PER_ANSWER + held * Scoring.POINTS_PER_LIKE,
            pointsOf(author),
        )
    }

    @Test
    fun `the feed shows each question's reactions and the player's own before they answer`() {
        val (fan, critic) = newPlayer() to newPlayer()
        val question = submitted(newPlayer())
        react(fan, question, Reaction.LIKE)
        react(critic, question, Reaction.DISLIKE)
        react(fan, SEED, Reaction.DISLIKE)

        val forFan = feed(fan).associateBy { it.id }
        val forCritic = feed(critic).associateBy { it.id }
        val forNobody = feed(newPlayer()).associateBy { it.id }

        assertEquals(false, forFan.getValue(question).answeredBefore, "liked, not answered")
        assertEquals(
            listOf(Triple(1, 1, Reaction.LIKE), Triple(0, 1, Reaction.DISLIKE)),
            listOf(question, SEED).map { forFan.getValue(it).reactions() },
        )
        assertEquals(
            listOf(Triple(1, 1, Reaction.DISLIKE), Triple(0, 1, Reaction.NONE)),
            listOf(question, SEED).map { forCritic.getValue(it).reactions() },
        )
        assertEquals(
            listOf(Triple(1, 1, Reaction.NONE), Triple(0, 1, Reaction.NONE)),
            listOf(question, SEED).map { forNobody.getValue(it).reactions() },
        )
        val rest = forNobody.filterKeys { it != question && it != SEED }.values
        assertEquals(
            setOf(Triple(0, 0, Reaction.NONE)),
            rest.map { it.reactions() }.toSet(),
            "and nobody's to the rest",
        )
    }

    @Test
    fun `a batch reads its reactions in one statement however many questions it holds`() {
        val pool = transaction(database) { Questions.select(Questions.id).map { it[Questions.id] } }
        val (fan, critic) = newPlayer() to newPlayer()
        pool.forEach { id ->
            react(fan, id, Reaction.LIKE)
            react(critic, id, Reaction.DISLIKE)
        }

        val statements =
            listOf(1, pool.size).map { limit ->
                val player = newPlayer()
                transaction(database) {
                    val batch = QuestionStore.feed(player, limit, categories = emptySet()).questions
                    assertEquals(limit, batch.size)
                    assertEquals(setOf(Triple(1, 1, Reaction.NONE)), batch.map { it.reactions() }.toSet())
                    statementCount
                }
            }

        assertEquals(statements.first(), statements.last(), "one statement per question would grow with the batch")
    }

    @Test
    fun `a change of mind committed while a batch's reactions are read shows in every number or in none`() {
        val player = newPlayer()
        react(player, SEED, Reaction.LIKE)
        val elsewhere = Executors.newSingleThreadExecutor()
        try {
            val served =
                transaction(database) {
                    // Commits the player's dislike right after the first statement that reads a reaction.
                    // Read one number per statement, the later ones would disagree with the first.
                    registerInterceptor(
                        afterFirstStatementOn(
                            "REACTIONS",
                            action = {
                                elsewhere
                                    .submit(Callable { react(player, SEED, Reaction.DISLIKE) })
                                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            },
                        ),
                    )
                    QuestionStore.feed(player, WyrApi.Limits.MAX_PAGE_SIZE, categories = emptySet()).questions
                }

            assertEquals(Triple(1, 0, Reaction.LIKE), served.single { it.id == SEED }.reactions(), "all from before")
            assertEquals(mapOf(player to Reaction.DISLIKE), reactionsTo(SEED), "and the dislike did land")
        } finally {
            elsewhere.shutdownNow()
        }
    }

    @Test
    fun `the stats count the likes the player's own questions hold, not their dislikes, and agree with their points`() {
        val author = newPlayer()
        val (first, second) = submitted(author) to submitted(author)
        val (fan, critic) = newPlayer() to newPlayer()
        react(fan, first, Reaction.LIKE)
        react(critic, first, Reaction.LIKE)
        react(fan, second, Reaction.LIKE)
        react(author, second, Reaction.LIKE)
        react(critic, first, Reaction.DISLIKE)
        react(critic, second, Reaction.DISLIKE)
        // Reactions the author gives count for the questions' authors, never for the author.
        react(author, submitted(fan), Reaction.LIKE)
        react(author, SEED, Reaction.LIKE)
        answer(author, first)

        val stats = statsOf(author)

        assertEquals(3, stats.likesReceived, "one left on the first, and the second's two, the author's own included")
        assertEquals(
            stats.answersGiven * Scoring.POINTS_PER_ANSWER + stats.likesReceived * Scoring.POINTS_PER_LIKE,
            stats.totalPoints,
        )
        assertEquals(1, statsOf(fan).likesReceived, "the author's like on the fan's question")
        assertEquals(0, statsOf(critic).likesReceived)
    }

    @Test
    fun `a question not approved cannot be reacted to by anybody`() {
        val (author, player) = newPlayer() to newPlayer()

        listOf(QuestionStatus.PENDING, QuestionStatus.REJECTED).forEach { status ->
            val question = submitted(author, status)
            listOf("its author" to author, "another player" to player).forEach { (who, id) ->
                Reaction.entries.forEach { reaction ->
                    assertNotFound("$status given $reaction by $who") { react(id, question, reaction) }
                }
            }
        }
        assertNotFound("a question that does not exist") { react(player, "no-such-question", Reaction.LIKE) }

        assertEquals(0L, transaction(database) { Reactions.selectAll().count() })
        assertEquals(0, pointsOf(author))
    }

    @Test
    fun `a player that does not exist cannot react`() {
        val failure = assertFailsWith<ApiFailure> { react("no-such-player", SEED, Reaction.LIKE) }

        assertEquals(ErrorCode.UNAUTHORIZED, failure.code)
    }

    @Test
    fun `two first likes racing on one question pay the author once`() {
        val (author, liker) = newPlayer() to newPlayer()
        val question = submitted(author)

        // The second finds no reaction either, inserts, waits on the first's key and fails on it once
        // the first commits. Only Exposed rerunning its whole transaction turns it into a repeat.
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { ReactionStore.set(liker, question, Reaction.LIKE) },
                { ReactionStore.set(liker, question, Reaction.LIKE) },
                queued = INSERTING_INTO_REACTIONS,
            )

        assertEquals(result(question, likes = 1, mine = Reaction.LIKE), first)
        assertEquals(result(question, likes = 1, mine = Reaction.LIKE), second, "the rerun found the like")
        assertEquals(mapOf(liker to Reaction.LIKE), reactionsTo(question))
        assertEquals(Scoring.POINTS_PER_LIKE, pointsOf(author), "paid once")
    }

    @Test
    fun `a first like and a first dislike racing leave the second, and the author's points to match`() {
        val (author, player) = newPlayer() to newPlayer()
        val question = submitted(author)

        // The dislike's insert fails on the like's key, and its rerun finds the like and replaces it.
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { ReactionStore.set(player, question, Reaction.LIKE) },
                { ReactionStore.set(player, question, Reaction.DISLIKE) },
                queued = INSERTING_INTO_REACTIONS,
            )

        assertEquals(result(question, likes = 1, mine = Reaction.LIKE), first)
        assertEquals(result(question, dislikes = 1, mine = Reaction.DISLIKE), second)
        assertEquals(mapOf(player to Reaction.DISLIKE), reactionsTo(question), "never both")
        assertEquals(0, pointsOf(author), "the like paid, and its replacement took it back")
    }

    @Test
    fun `a like taken back and replaced at once take its point back once`() {
        val (author, player) = newPlayer() to newPlayer()
        val question = submitted(author)
        react(player, question, Reaction.LIKE)

        // The second waits on the row lock the first's read holds, then reads the row gone.
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { ReactionStore.set(player, question, Reaction.NONE) },
                { ReactionStore.set(player, question, Reaction.DISLIKE) },
            )

        assertEquals(result(question), first)
        assertEquals(result(question, dislikes = 1, mine = Reaction.DISLIKE), second)
        assertEquals(mapOf(player to Reaction.DISLIKE), reactionsTo(question))
        assertEquals(0, pointsOf(author), "taken back once, not twice")
    }

    @Test
    fun `two likes taken back at once take the point back once`() {
        val (author, liker) = newPlayer() to newPlayer()
        val question = submitted(author)
        react(liker, question, Reaction.LIKE)

        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { ReactionStore.set(liker, question, Reaction.NONE) },
                { ReactionStore.set(liker, question, Reaction.NONE) },
            )

        assertEquals(result(question), first)
        assertEquals(result(question), second)
        assertEquals(0, pointsOf(author), "taken back once, not twice")
    }

    private fun newPlayer(): String = transaction(database) { PlayerStore.createGuest().id }

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
                row[category] = "FOOD"
            }
        }
        return id
    }

    private fun react(
        player: String,
        questionId: String,
        reaction: Reaction,
    ): ReactionResultDto = transaction(database) { ReactionStore.set(player, questionId, reaction) }

    /** What a reaction to [questionId] answers, with these counts and the player's own [mine]. */
    private fun result(
        questionId: String,
        likes: Int = 0,
        dislikes: Int = 0,
        mine: Reaction = Reaction.NONE,
    ) = ReactionResultDto(questionId, likeCount = likes, dislikeCount = dislikes, myReaction = mine)

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

    /** A served question's like and dislike counts and what the player it was served to thinks of it. */
    private fun QuestionDto.reactions(): Triple<Int, Int, Reaction> = Triple(likeCount, dislikeCount, myReaction)

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

    /** Every reaction held to [questionId], by player, straight from the table. */
    private fun reactionsTo(questionId: String): Map<String, Reaction> =
        transaction(database) {
            Reactions
                .select(Reactions.playerId, Reactions.reaction)
                .where { Reactions.questionId eq questionId }
                .associate { it[Reactions.playerId] to it[Reactions.reaction] }
        }

    /** The players who like [questionId], straight from the table. */
    private fun likersOf(questionId: String): List<String> =
        reactionsTo(questionId).filterValues { it == Reaction.LIKE }.keys.toList()

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

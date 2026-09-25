package io.ntole.wyr.server.moderation

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Reactions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.Skips
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.player.StatsStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import io.ntole.wyr.server.question.SkipStore
import io.ntole.wyr.server.question.SubmissionStore
import io.ntole.wyr.server.question.paidSubmission
import io.ntole.wyr.server.reaction.ReactionStore
import io.ntole.wyr.server.vote.Scoring
import io.ntole.wyr.server.vote.VoteStore
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Retiring and restoring approved questions (CLAUDE.md §8d, *Moderation*) through the stores at READ
 * COMMITTED, as in ModerationStoreTest, so two moderators, or a moderator and a player, can be raced.
 */
class RetirementTest {
    private val url = h2Url("wyr-retirement-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    private val seeds: List<String> =
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.questionsIfEmpty()
            Questions.select(Questions.id).map { it[Questions.id] }
        }

    @Test
    fun `a retired question is served to nobody and due for nobody until it is restored`() {
        val author = newPlayer()
        val player = newPlayer()
        val question = approved(author)

        val retired = transaction(database) { ModerationStore.retire(question, now = 9_000L) }

        assertEquals(QuestionStatus.RETIRED to 9_000L, retired.status to retired.retiredAt)
        listOf("its author" to author, "another player" to player).forEach { (who, id) ->
            assertEquals(seeds.sorted(), feed(id).ids().sorted(), "$who is served only the seeds")
            assertEquals(seeds.size, statsOf(id).dueThisCycle, "and the due count on the feed's predicate agrees")
            assertFalse(question in feed(id, setOf("FOOD")).ids(), "$who is not served it by category")
        }

        val restored = transaction(database) { ModerationStore.restore(question) }

        assertEquals(QuestionStatus.APPROVED to null, restored.status to restored.retiredAt)
        assertEquals((seeds + question).sorted(), feed(player).ids().sorted(), "served again")
        assertEquals(seeds.size + 1, statsOf(player).dueThisCycle)
    }

    @Test
    fun `a cycle finishes without a retired question, and a restored one is due for whoever has not done it`() {
        val (finishing, answered) = newPlayer() to newPlayer()
        (seeds - SEED).forEach { id -> answer(finishing, id) }
        answer(answered, SEED)

        retire(SEED)

        assertEquals(0, statsOf(finishing).dueThisCycle, "nothing left but the retired one")
        assertEquals((seeds - SEED).sorted(), feed(finishing).ids().sorted(), "so the next cycle starts without it")
        assertEquals(2, cycleOf(finishing))

        restore(SEED)

        assertEquals(seeds.size, statsOf(finishing).dueThisCycle, "never answered in cycle 2, so due in it")
        assertEquals(seeds.size - 1, statsOf(answered).dueThisCycle, "answered in the cycle the player is on")
        assertFalse(SEED in feed(answered).ids())
    }

    @Test
    fun `nobody can answer, skip or react to a retired question, and its reactions stay held`() {
        val author = newPlayer()
        val liker = newPlayer()
        val critic = newPlayer()
        val question = approved(author)
        react(liker, question, Reaction.LIKE)
        react(critic, question, Reaction.DISLIKE)
        retire(question)

        listOf(author, liker, critic).forEach { who ->
            assertNotFound("answered") { answer(who, question) }
            assertNotFound("skipped") { transaction(database) { SkipStore.skip(who, question) } }
            Reaction.entries.forEach { reaction ->
                assertNotFound("given $reaction") { react(who, question, reaction) }
            }
        }

        assertEquals(mapOf(liker to Reaction.LIKE, critic to Reaction.DISLIKE), reactionsTo(question), "still held")
        assertEquals(Scoring.POINTS_PER_LIKE, statsOf(author).totalPoints, "and still paid")
    }

    @Test
    fun `retiring takes back nothing, so every number but what is due stays as it was`() {
        val author = newPlayer()
        val player = newPlayer()
        val question = approved(author)
        answer(player, question)
        answer(author, SEED)
        react(player, question, Reaction.LIKE)
        react(author, question, Reaction.LIKE)
        val before = listOf(author, player).associateWith { statsOf(it) }
        val listedBefore = listed(question)

        retire(question)

        assertEquals(before.getValue(player), statsOf(player), "answered already, so not due before either")
        assertEquals(
            before.getValue(author).copy(dueThisCycle = before.getValue(author).dueThisCycle - 1),
            statsOf(author),
            "only no longer due",
        )
        statsOf(author).let { stats ->
            assertEquals(stats.answersGiven + stats.likesReceived, stats.totalPoints, "points still add up (§8c)")
            assertEquals(2, stats.likesReceived)
        }
        assertEquals(
            listedBefore.copy(status = QuestionStatus.RETIRED, retiredAt = listed(question).retiredAt),
            listed(question),
            "its tally and its reaction counts kept",
        )
    }

    @Test
    fun `its author sees a retired question as retired, and approved again once it is restored`() {
        val author = newPlayer()
        val question = approved(author)

        retire(question)
        assertEquals(listOf(QuestionStatus.RETIRED), mySubmissions(author).map { it.status })

        restore(question)
        assertEquals(listOf(QuestionStatus.APPROVED), mySubmissions(author).map { it.status })
    }

    @Test
    fun `a retired question stands at retired in the queue and the list, never at approved`() {
        val author = newPlayer()
        val (kept, retired) = approved(author) to approved(author)
        retire(retired)
        retire(SEED)

        assertEquals(listOf(kept), queue(QuestionStatus.APPROVED).map { it.id })
        assertEquals(listOf(retired), queue(QuestionStatus.RETIRED).map { it.id }, "the players' own, never a seed")
        assertEquals(setOf(retired, SEED), listIds(QuestionStatus.RETIRED).toSet())
        assertEquals(((seeds - SEED) + kept).toSet(), listIds(QuestionStatus.APPROVED).toSet())
    }

    @Test
    fun `only an approved question is retired and only a retired one restored, and a refusal changes nothing`() {
        val author = newPlayer()
        val pending = submit(author)
        val rejected = submit(author).also { transaction(database) { ModerationStore.reject(it, "No") } }
        val retired = approved(author).also { retire(it) }
        val kept = approved(author)
        val before = listOf(pending, rejected, retired, kept, SEED).associateWith { listed(it) }

        listOf(pending, rejected, retired).forEach { id ->
            assertRefused(ErrorCode.WRONG_STATUS, "retiring ${listed(id).status}") { ModerationStore.retire(id) }
        }
        listOf(pending, rejected, kept, SEED).forEach { id ->
            assertRefused(ErrorCode.WRONG_STATUS, "restoring ${listed(id).status}") { ModerationStore.restore(id) }
        }
        listOf<
            (
                String,
            ) -> AdminQuestionDto,
        >({ ModerationStore.retire(it) }, { ModerationStore.restore(it) }).forEach { move ->
            assertRefused(ErrorCode.QUESTION_NOT_FOUND, "an id no question has") { move("no-such-question") }
        }

        assertEquals(before, before.keys.associateWith { listed(it) })
    }

    @Test
    fun `a seed can be retired and restored like any approved question`() {
        val retired = transaction(database) { ModerationStore.retire(SEED, now = 9_000L) }

        assertEquals(QuestionStatus.RETIRED to true, retired.status to retired.seed)
        assertFalse(SEED in feed(newPlayer()).ids())
        assertEquals(QuestionStatus.APPROVED, transaction(database) { ModerationStore.restore(SEED) }.status)
    }

    @Test
    fun `two moderators retiring one question at once let exactly one retire it`() {
        val question = approved(newPlayer())

        // The second's update waits on the first's row lock. By id and status alone it would then match
        // the row the first committed, still approved, and retire the question again, later.
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { runCatching { ModerationStore.retire(question, now = 5_000L) } },
                { runCatching { ModerationStore.retire(question, now = 6_000L) } },
            )

        assertEquals(5_000L, first.getOrThrow().retiredAt)
        assertEquals(ErrorCode.WRONG_STATUS, (second.exceptionOrNull() as? ApiFailure)?.code)
        assertEquals(5_000L, listed(question).retiredAt, "the winner's")
    }

    @Test
    fun `two moderators restoring one question at once let exactly one restore it`() {
        val question = approved(newPlayer())
        retire(question)

        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { runCatching { ModerationStore.restore(question) } },
                { runCatching { ModerationStore.restore(question) } },
            )

        assertEquals(QuestionStatus.APPROVED, first.getOrThrow().status)
        assertEquals(ErrorCode.WRONG_STATUS, (second.exceptionOrNull() as? ApiFailure)?.code)
    }

    @Test
    fun `a vote, skip or reaction in flight as a retirement commits still lands`() {
        val player = newPlayer()
        val (voted, skipped, liked) = List(3) { approved(newPlayer()) }

        // Each reads its question as servable with no lock, so the retirement committed right after
        // that read does not wait for it, and the write lands on a question already retired
        // (CLAUDE.md §8b, *Retiring a question*). Locked, the retirement would wait, and time out here.
        retiredMidway(voted) { VoteStore.cast(player, voted, OptionSide.B, attemptId = "in-flight") }
        retiredMidway(skipped) { SkipStore.skip(player, skipped) }
        retiredMidway(liked) { ReactionStore.set(player, liked, Reaction.LIKE) }

        listOf(voted, skipped, liked).forEach { id -> assertEquals(QuestionStatus.RETIRED, listed(id).status) }
        assertEquals(VoteTallyDto(votesA = 0, votesB = 1), listed(voted).tally, "the vote landed")
        assertEquals(listOf(player), skippersOf(skipped), "the skip landed")
        assertEquals(mapOf(player to Reaction.LIKE), reactionsTo(liked), "the like landed")
    }

    private fun newPlayer(): String = transaction(database) { PlayerStore.createGuest().id }

    /** A food question by [author], waiting for a moderator. */
    private fun submit(author: String): String {
        val tag = UUID.randomUUID().toString().take(8)
        val request = SubmitQuestionRequest("Option $tag", "Other $tag", listOf("FOOD"))
        return transaction(database) { paidSubmission(author, request).id }
    }

    /** A food question by [author], approved. */
    private fun approved(author: String): String =
        submit(author).also { id -> transaction(database) { ModerationStore.approve(id, emptyList()) } }

    private fun retire(id: String) {
        transaction(database) { ModerationStore.retire(id) }
    }

    private fun restore(id: String) {
        transaction(database) { ModerationStore.restore(id) }
    }

    private fun answer(
        player: String,
        questionId: String,
    ) {
        transaction(database) {
            VoteStore.cast(player, questionId, OptionSide.A, attemptId = UUID.randomUUID().toString())
        }
    }

    private fun react(
        player: String,
        questionId: String,
        reaction: Reaction,
    ) {
        transaction(database) { ReactionStore.set(player, questionId, reaction) }
    }

    /**
     * Runs [write] in a transaction of its own, and retires [question] in another as soon as [write]
     * has read the question, before it writes anything.
     */
    private fun retiredMidway(
        question: String,
        write: () -> Unit,
    ) {
        val elsewhere = Executors.newSingleThreadExecutor()
        try {
            transaction(database) {
                registerInterceptor(
                    afterFirstReadOfQuestions {
                        elsewhere.submit(Callable { retire(question) }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    },
                )
                write()
            }
        } finally {
            elsewhere.shutdownNow()
        }
    }

    /** Runs [action] once, after the transaction's first statement that reads `questions`. */
    private fun afterFirstReadOfQuestions(action: () -> Unit): StatementInterceptor =
        object : StatementInterceptor {
            private var fired = false

            override fun afterExecution(
                transaction: Transaction,
                contexts: List<StatementContext>,
                executedStatement: PreparedStatementApi,
            ) {
                if (fired || contexts.none { "FROM QUESTIONS" in it.sql(transaction).uppercase() }) return
                fired = true
                action()
            }
        }

    private fun skippersOf(questionId: String): List<String> =
        transaction(database) {
            Skips.select(Skips.playerId).where { Skips.questionId eq questionId }.map { it[Skips.playerId] }
        }

    private fun reactionsTo(questionId: String): Map<String, Reaction> =
        transaction(database) {
            Reactions
                .select(Reactions.playerId, Reactions.reaction)
                .where { Reactions.questionId eq questionId }
                .associate { it[Reactions.playerId] to it[Reactions.reaction] }
        }

    private fun feed(
        player: String,
        categories: Set<String> = emptySet(),
    ): List<QuestionDto> =
        transaction(database) { QuestionStore.feed(player, WyrApi.Limits.MAX_PAGE_SIZE, categories).questions }

    private fun statsOf(player: String): PlayerStatsDto =
        checkNotNull(transaction(database) { StatsStore.of(player) }) { "player $player has no stats" }

    private fun cycleOf(player: String): Int? = transaction(database) { PlayerStore.find(player)?.cycle }

    private fun mySubmissions(author: String): List<SubmissionDto> =
        transaction(database) { SubmissionStore.byAuthor(author) }

    private fun queue(status: QuestionStatus): List<SubmissionDto> =
        transaction(database) { ModerationStore.queue(status, limit = 100) }

    private fun listIds(status: QuestionStatus): List<String> =
        transaction(database) {
            ModerationStore.questions(setOf(status), emptySet(), after = null, limit = 100).questions.map { it.id }
        }

    /** The question [id] as the moderator's list shows it. */
    private fun listed(id: String): AdminQuestionDto =
        transaction(database) {
            ModerationStore
                .questions(
                    emptySet(),
                    emptySet(),
                    after = null,
                    limit = 100,
                ).questions
                .single { it.id == id }
        }

    private fun assertRefused(
        code: ErrorCode,
        case: String,
        move: () -> AdminQuestionDto,
    ) {
        val failure = assertFailsWith<ApiFailure>(case) { transaction(database) { move() } }
        assertEquals(code, failure.code, case)
    }

    private fun assertNotFound(
        case: String,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<ApiFailure>(case) { block() }
        assertEquals(ErrorCode.QUESTION_NOT_FOUND, failure.code, case)
    }

    private fun List<QuestionDto>.ids(): List<String> = map { it.id }

    private companion object {
        const val SEED = "seed-1"
        const val TIMEOUT_SECONDS = 10L
    }
}

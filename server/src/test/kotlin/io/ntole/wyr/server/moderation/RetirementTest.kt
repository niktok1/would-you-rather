package io.ntole.wyr.server.moderation

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.Likes
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.like.LikeStore
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.player.StatsStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import io.ntole.wyr.server.question.SkipStore
import io.ntole.wyr.server.question.SubmissionStore
import io.ntole.wyr.server.vote.Scoring
import io.ntole.wyr.server.vote.VoteStore
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
            assertFalse(question in feed(id, setOf(QuestionCategory.FOOD)).ids(), "$who is not served it by category")
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
    fun `nobody can answer, skip, like or unlike a retired question, and its likes stay held`() {
        val author = newPlayer()
        val liker = newPlayer()
        val question = approved(author)
        like(liker, question, liked = true)
        retire(question)

        listOf(author, liker).forEach { who ->
            assertNotFound("answered") { answer(who, question) }
            assertNotFound("skipped") { transaction(database) { SkipStore.skip(who, question) } }
            assertNotFound("liked") { like(who, question, liked = true) }
            assertNotFound("unliked") { like(who, question, liked = false) }
        }

        assertEquals(listOf(liker), likersOf(question), "the like still held")
        assertEquals(Scoring.POINTS_PER_LIKE, statsOf(author).totalPoints, "and still paid")
    }

    @Test
    fun `retiring takes back nothing, so every number but what is due stays as it was`() {
        val author = newPlayer()
        val player = newPlayer()
        val question = approved(author)
        answer(player, question)
        answer(author, SEED)
        like(player, question, liked = true)
        like(author, question, liked = true)
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
            "its tally and its like count kept",
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
    fun `a retirement waits for a vote that found the question servable, and answers with it counted`() {
        val player = newPlayer()
        val question = approved(newPlayer())

        // The vote holds the question's row. Read without a lock, the retirement would not wait, and
        // the vote would land on a question the moderator had been told was retired.
        val (vote, retired) =
            raceBehindFirst(
                url,
                database,
                { runCatching { VoteStore.cast(player, question, OptionSide.B, attemptId = "in-flight") } },
                { runCatching { ModerationStore.retire(question) } },
            )

        assertEquals(Scoring.POINTS_PER_ANSWER, (vote.getOrThrow() as VoteResultDto).pointsAwarded, "the vote landed")
        val listed = retired.getOrThrow() as AdminQuestionDto
        assertEquals(QuestionStatus.RETIRED to VoteTallyDto(votesA = 0, votesB = 1), listed.status to listed.tally)
    }

    @Test
    fun `a vote, skip, like or unlike waiting on a retirement is not found once it commits`() {
        val player = newPlayer()
        val actions: List<Pair<String, (String) -> Unit>> =
            listOf(
                "a vote" to { id -> answer(player, id) },
                "a skip" to { id -> SkipStore.skip(player, id) },
                "a like" to { id -> LikeStore.setLiked(player, id, liked = true) },
                "an unlike" to { id -> LikeStore.setLiked(player, id, liked = false) },
            )

        actions.forEach { (what, action) ->
            val question = approved(newPlayer())
            if (what == "an unlike") like(player, question, liked = true)

            // The retirement's update holds the question's row, and the action's read waits on it. Read
            // without a lock, it would find the question approved, as the last commit left it.
            val (retired, acted) =
                raceBehindFirst(
                    url,
                    database,
                    { runCatching { ModerationStore.retire(question) } },
                    { runCatching { action(question) } },
                )

            assertTrue(retired.isSuccess, what)
            assertEquals(ErrorCode.QUESTION_NOT_FOUND, (acted.exceptionOrNull() as? ApiFailure)?.code, what)
        }
        assertEquals(0, statsOf(player).answersGiven, "no answer landed")
    }

    private fun newPlayer(): String =
        transaction(database) { PlayerStore.createGuest(UUID.randomUUID().toString(), Long.MAX_VALUE).id }

    /** A food question by [author], waiting for a moderator. */
    private fun submit(author: String): String {
        val tag = UUID.randomUUID().toString().take(8)
        val request = SubmitQuestionRequest("Option $tag", "Other $tag", listOf(QuestionCategory.FOOD))
        return transaction(database) { SubmissionStore.submit(author, request).id }
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

    private fun like(
        player: String,
        questionId: String,
        liked: Boolean,
    ) {
        transaction(database) { LikeStore.setLiked(player, questionId, liked) }
    }

    private fun likersOf(questionId: String): List<String> =
        transaction(database) {
            Likes.select(Likes.playerId).where { Likes.questionId eq questionId }.map { it[Likes.playerId] }
        }

    private fun feed(
        player: String,
        categories: Set<QuestionCategory> = emptySet(),
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
    }
}

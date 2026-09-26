package io.ntole.wyr.server.player

import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.core.reaction.ReactionResultDto
import io.ntole.wyr.core.report.ReportReason
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.server.auth.AccountStore
import io.ntole.wyr.server.auth.SessionStore
import io.ntole.wyr.server.db.HiddenAuthors
import io.ntole.wyr.server.db.HiddenQuestions
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Reactions
import io.ntole.wyr.server.db.Reports
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.Sessions
import io.ntole.wyr.server.db.Skips
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.moderation.ModerationStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import io.ntole.wyr.server.question.SkipStore
import io.ntole.wyr.server.reaction.ReactionStore
import io.ntole.wyr.server.report.ReportStore
import io.ntole.wyr.server.vote.Scoring
import io.ntole.wyr.server.vote.VoteStore
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Deleting an account (CLAUDE.md §8a, *Deleting an account*) through the stores at READ COMMITTED, on
 * the first seeds, as ReactionStoreTest drives reactions: so what is left in every table can be read
 * straight after, and a like racing the deletion staged.
 */
class AccountDeletionTest {
    private val url = h2Url("wyr-account-deletion-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.writeMissing(TEST_SEEDS)
        }
    }

    @Test
    fun `a deletion takes everything of the player's and leaves their approved questions to nobody`() {
        val (deleted, other, hider) = List(3) { newPlayer() }
        transaction(database) {
            AccountStore.register(deleted, "gone", "not-a-password-hash")
            SessionStore.open(deleted, refreshTokenHash = "h-${UUID.randomUUID()}", expiresAt = Long.MAX_VALUE)
        }
        val approved = question(deleted)
        val retired = question(deleted)
        val pending = question(deleted, QuestionStatus.PENDING)
        val rejected = question(deleted, QuestionStatus.PENDING).also { reject(it) }
        val someoneElses = question(other)
        transaction(database) {
            VoteStore.cast(deleted, "seed-1", OptionSide.A, attemptId = "a1")
            SkipStore.skip(deleted, "seed-2")
            ReactionStore.set(deleted, "seed-3", Reaction.DISLIKE)
            ReportStore.report(deleted, "seed-4", ReportReason.SPAM)
            ReportStore.hideAuthorOf(deleted, someoneElses)
            VoteStore.cast(other, approved, OptionSide.B, attemptId = "b1")
            ReactionStore.set(other, approved, Reaction.LIKE)
            ReportStore.hideAuthorOf(hider, approved)
            ModerationStore.retire(retired)
        }

        transaction(database) { AccountDeletion.delete(deleted) }

        assertEquals(0, rowsOf(Players.id, deleted), "their row, username and password hash with it")
        listOf(
            Sessions.playerId,
            Votes.playerId,
            Skips.playerId,
            Reactions.playerId,
            Reports.playerId,
            HiddenQuestions.playerId,
            HiddenAuthors.playerId,
            HiddenAuthors.authorPlayerId,
            Questions.authorPlayerId,
        ).forEach { column -> assertEquals(0, rowsOf(column, deleted), "no ${column.name} names them") }
        assertEquals(0, rowsOf(Questions.id, pending) + rowsOf(Questions.id, rejected), "never served, so gone")
        assertEquals(0, rowsOf(QuestionCategories.questionId, pending), "and filed under nothing")
        assertEquals(
            listOf(QuestionStatus.APPROVED to null, QuestionStatus.APPROVED to null),
            listOf(approved, retired).map { id -> statusAndAuthorOf(id) },
            "approved ones stay, a retired one retired, by nobody",
        )
        assertEquals(1, rowsOf(Votes.questionId, approved), "another's answer stays")
        assertEquals(1, rowsOf(Reactions.questionId, approved), "and their like")
        assertEquals(setOf(approved, retired), hiddenFrom(hider), "hidden one by one from who hid the author")
        transaction(database) { AccountStore.register(other, "gone", "not-a-password-hash") }
    }

    @Test
    fun `each like the deleted player held is taken back from its author and every other author's sum holds`() {
        val (deleted, first, second, liker) = List(4) { newPlayer() }
        val (firstA, firstB) = question(first) to question(first)
        val (secondA, secondB) = question(second) to question(second)
        val own = question(deleted)
        transaction(database) {
            listOf(firstA, firstB, secondA, own, "seed-1").forEach { ReactionStore.set(deleted, it, Reaction.LIKE) }
            ReactionStore.set(deleted, secondB, Reaction.DISLIKE)
            ReactionStore.set(liker, firstA, Reaction.LIKE)
            VoteStore.cast(first, "seed-1", OptionSide.A, attemptId = "a1")
        }
        val before = listOf(first, second).associateWith { pointsOf(it) }

        transaction(database) { AccountDeletion.delete(deleted) }

        assertEquals(before.getValue(first) - 2 * Scoring.POINTS_PER_LIKE, pointsOf(first), "two likes taken back")
        assertEquals(before.getValue(second) - Scoring.POINTS_PER_LIKE, pointsOf(second), "one; a dislike paid nothing")
        listOf(first, second, liker).forEach { author ->
            val stats = statsOf(author)
            assertEquals(
                stats.answersGiven * Scoring.POINTS_PER_ANSWER + stats.likesReceived * Scoring.POINTS_PER_LIKE -
                    stats.pointsSpent,
                stats.totalPoints,
                "what $author's answers earned, and the likes their questions hold, less what they cost",
            )
        }
        assertEquals(1, statsOf(first).likesReceived, "the liker's like stays")
    }

    @Test
    fun `a player who is gone is 401 and nothing is changed`() {
        val failure = assertFailsWith<ApiFailure> { transaction(database) { AccountDeletion.delete("no-such-player") } }

        assertEquals(ErrorCode.UNAUTHORIZED, failure.code)
    }

    @Test
    fun `a re-answer from another device waiting on the deletion is 401`() {
        val deleted = newPlayer()
        transaction(database) { VoteStore.cast(deleted, "seed-1", OptionSide.A, attemptId = "a1") }

        val refusal =
            refusalBehindDeletion(deleted) { VoteStore.cast(deleted, "seed-1", OptionSide.B, attemptId = "a2") }

        assertEquals(ErrorCode.UNAUTHORIZED, refusal.code)
    }

    @Test
    fun `a re-skip from another device waiting on the deletion is 401`() {
        val deleted = newPlayer()
        transaction(database) { SkipStore.skip(deleted, "seed-1") }

        val refusal = refusalBehindDeletion(deleted) { SkipStore.skip(deleted, "seed-1") }

        assertEquals(ErrorCode.UNAUTHORIZED, refusal.code)
    }

    @Test
    fun `a feed read that finds the player deleted is 401`() {
        val deleted = newPlayer()
        transaction(database) { AccountDeletion.delete(deleted) }

        val refusal =
            assertFailsWith<ApiFailure> { transaction(database) { QuestionStore.feed(deleted, 10, emptySet()) } }

        assertEquals(ErrorCode.UNAUTHORIZED, refusal.code)
    }

    @Test
    fun `a registration waiting on the deletion is 401`() {
        val deleted = newPlayer()

        val refusal = refusalBehindDeletion(deleted) { AccountStore.register(deleted, "late", "not-a-password-hash") }

        assertEquals(ErrorCode.UNAUTHORIZED, refusal.code)
    }

    @Test
    fun `a like of the deleted player's question in flight lands and pays nobody`() {
        val (deleted, liker) = newPlayer() to newPlayer()
        val approved = question(deleted)

        val (_, liked) =
            raceBehindFirst<Any>(
                url,
                database,
                { AccountDeletion.delete(deleted) },
                { ReactionStore.set(liker, approved, Reaction.LIKE) },
            )

        assertEquals(1, assertIs<ReactionResultDto>(liked).likeCount)
        assertEquals(QuestionStatus.APPROVED to null, statusAndAuthorOf(approved), "by nobody")
        assertEquals(0, rowsOf(Players.id, deleted))
    }

    /**
     * What [request] by [deleted] is refused with when it waits on the deletion of [deleted]'s account
     * for a lock and then finds what it waited on gone, as PostgreSQL's READ COMMITTED leaves it.
     */
    private fun refusalBehindDeletion(
        deleted: String,
        request: () -> Unit,
    ): ApiFailure {
        val (_, refused) =
            raceBehindFirst<Any?>(
                url,
                database,
                { AccountDeletion.delete(deleted) },
                { runCatching(request).exceptionOrNull() },
            )
        return assertIs<ApiFailure>(refused)
    }

    private fun newPlayer(): String = transaction(database) { PlayerStore.createGuest().id }

    /** A food question by [author], stored as a moderator's decision of [status] would leave it. */
    private fun question(
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
            }
            QuestionCategories.insert { row ->
                row[questionId] = id
                row[category] = "FOOD"
            }
        }
        return id
    }

    private fun reject(questionId: String) = transaction(database) { ModerationStore.reject(questionId, "No") }

    /** How many rows have [value] in [column], straight from its table. */
    private fun rowsOf(
        column: Column<*>,
        value: String,
    ): Int =
        transaction(database) {
            @Suppress("UNCHECKED_CAST")
            val named = column as Column<Any?>
            column.table
                .selectAll()
                .where { named inList listOf(value) }
                .count()
                .toInt()
        }

    private fun statusAndAuthorOf(questionId: String): Pair<QuestionStatus, String?> =
        transaction(database) {
            Questions
                .select(Questions.status, Questions.authorPlayerId)
                .where { Questions.id eq questionId }
                .single()
                .let { it[Questions.status] to it[Questions.authorPlayerId] }
        }

    private fun hiddenFrom(player: String): Set<String> =
        transaction(database) {
            HiddenQuestions
                .select(HiddenQuestions.questionId)
                .where { HiddenQuestions.playerId eq player }
                .map { it[HiddenQuestions.questionId] }
                .toSet()
        }

    private fun pointsOf(player: String): Int = statsOf(player).totalPoints

    private fun statsOf(player: String): PlayerStatsDto =
        checkNotNull(transaction(database) { StatsStore.of(player) }) { "no player $player" }
}

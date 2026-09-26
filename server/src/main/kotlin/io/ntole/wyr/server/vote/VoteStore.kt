package io.ntole.wyr.server.vote

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

object VoteStore {
    /**
     * Records an answer, counts it, and scores it. Must run inside a transaction.
     *
     * A player holds one vote per question, their latest (CLAUDE.md §8d). A first answer inserts
     * it; answering again moves it to the new side and pays again, even within the cycle it was
     * last answered in (§8b leaves that to rate limiting), and counts as another answer given.
     * Either way the vote records the player's current cycle, so the feed does not serve the
     * question again until the next one. `created_at` keeps when the player first answered and
     * `answered_at` when they last did.
     *
     * Every answer carries the client's [attemptId], and the vote keeps the latest. A request that
     * repeats it is a retry of an answer already recorded, and is replayed: nothing is written, it
     * pays nothing, counts as no answer, and it reports the stored side with the current tally and
     * total.
     *
     * [answerMillis] is how long the answer took, already bounded ([keptAnswerMillis]), or null; the
     * vote keeps the latest answer's, as it keeps its side, and a replay leaves it alone.
     *
     * A question the player may not be served is not found ([QuestionStore.isServable]): one a
     * moderator has not approved, or has retired. The player's own is served like any other.
     *
     * Ordering matters twice. The player is resolved *before* any write: a validly signed token
     * can outlive its player (an H2 dev server restarted with the constant dev secret still
     * accepts yesterday's tokens), and inserting first would trip the Votes foreign key instead
     * of answering 401. And the vote is written *before* the tally is counted, so the returned
     * percentages already include the player's own vote — which is what the reveal screen shows.
     *
     * Two first answers racing on one question both find no vote and both insert. The second waits
     * on the first's uncommitted key, then fails on the primary key (SQLState 23505) once it
     * commits. That failure is deliberately not caught: PostgreSQL aborts a transaction at its
     * first error, so nothing more could run in this one. It propagates, Exposed rolls back and
     * runs the whole transaction again (`Db.query` goes through `transaction`, 3 attempts). The
     * rerun finds the committed vote and treats it like any other: a different attempt moves it
     * (two answers, one vote), and the same attempt replays it.
     */
    fun cast(
        playerId: String,
        questionId: String,
        choice: OptionSide,
        attemptId: String,
        answerMillis: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): VoteResultDto {
        if (!QuestionStore.isServable(questionId)) throw ApiFailure.questionNotFound(questionId)

        if (PlayerStore.find(playerId) == null) throw ApiFailure.unauthorized("unknown player")

        val stored = lockVote(playerId, questionId)

        if (stored != null && stored[Votes.attemptId] == attemptId) return replay(playerId, questionId, stored)

        val cycle = currentCycle(playerId)

        val answer = Answer(choice, attemptId, answerMillis, cycle, now)
        if (stored == null) insertVote(playerId, questionId, answer) else moveVote(playerId, questionId, answer)

        val tally = tally(questionId)

        PlayerStore.countAnswer(playerId)
        val totalPoints = PlayerStore.addPoints(playerId = playerId, points = Scoring.POINTS_PER_ANSWER)

        return VoteResultDto(
            questionId = questionId,
            yourChoice = choice,
            tally = tally,
            pointsAwarded = Scoring.POINTS_PER_ANSWER,
            totalPoints = totalPoints,
            replayed = false,
        )
    }

    /**
     * The player's vote on the question, locked until this transaction ends, or null before their
     * first answer.
     *
     * Locked because what happens next depends on the attempt id read here, and at READ COMMITTED a
     * plain read can be stale by the time this transaction writes: a retry arriving while its
     * attempt is still in flight would read the attempt before it, take itself for a fresh answer,
     * and pay again. `FOR UPDATE` makes it wait for the attempt to commit and then read the row that
     * attempt left, and so replay it. Before a first answer there is no row to lock, and two first
     * answers race on the primary key instead (see [cast]).
     */
    private fun lockVote(
        playerId: String,
        questionId: String,
    ): ResultRow? =
        Votes
            .select(Votes.side, Votes.attemptId)
            .where { (Votes.playerId eq playerId) and (Votes.questionId eq questionId) }
            .forUpdate()
            .singleOrNull()

    /**
     * The cycle this answer counts for (CLAUDE.md §8d). Read after [lockVote], not taken from the
     * check in [cast], for the reason [replay] reads the total then. An answer that waited on the
     * lock for another answer to the same question may find that the feed started the next cycle
     * meanwhile, and it lands in that one. Taken from before the wait, it would count for the cycle
     * before, and its question would come round again in the cycle it was just answered in.
     *
     * The feed can still start a cycle between this read and the commit, and that is harmless. It
     * starts one only when nothing is due, and this answer is not committed yet, so its question
     * was already answered or skipped in the cycle read here: counting this answer there too is what
     * answering just before the new cycle would have done.
     */
    private fun currentCycle(playerId: String): Int =
        checkNotNull(PlayerStore.find(playerId)) { "player $playerId vanished mid-transaction" }.cycle

    private fun replay(
        playerId: String,
        questionId: String,
        stored: ResultRow,
    ): VoteResultDto {
        // Read now, not taken from the check in cast: that ran before any wait for the lock, and the
        // attempt being replayed may have paid its point since.
        val player = checkNotNull(PlayerStore.find(playerId)) { "player $playerId vanished mid-transaction" }
        return VoteResultDto(
            questionId = questionId,
            yourChoice = OptionSide.valueOf(stored[Votes.side]),
            tally = tally(questionId),
            pointsAwarded = 0,
            totalPoints = player.totalPoints,
            replayed = true,
        )
    }

    /** What one answer writes to the vote: the side, its attempt and time taken, the cycle it counts for, and when. */
    private class Answer(
        val choice: OptionSide,
        val attemptId: String,
        val answerMillis: Long?,
        val cycle: Int,
        val at: Long,
    )

    private fun insertVote(
        playerId: String,
        questionId: String,
        answer: Answer,
    ) {
        Votes.insert { row ->
            row[Votes.playerId] = playerId
            row[Votes.questionId] = questionId
            row[side] = answer.choice.name
            row[createdAt] = answer.at
            row[answeredAt] = answer.at
            row[answeredInCycle] = answer.cycle
            row[attemptId] = answer.attemptId
            row[answerMillis] = answer.answerMillis
        }
    }

    /** Under [lockVote]'s lock, so an update by key is exactly the row that was read. */
    private fun moveVote(
        playerId: String,
        questionId: String,
        answer: Answer,
    ) {
        val moved =
            Votes.update({ (Votes.playerId eq playerId) and (Votes.questionId eq questionId) }) { row ->
                row[side] = answer.choice.name
                row[answeredAt] = answer.at
                row[answeredInCycle] = answer.cycle
                row[attemptId] = answer.attemptId
                row[answerMillis] = answer.answerMillis
            }
        // Nothing deletes a vote, and this transaction holds its lock.
        check(moved == 1) { "vote by $playerId on $questionId vanished mid-transaction" }
    }

    /**
     * Both sides of the question's tally, its made-up votes included, in one statement
     * ([QuestionTally]). At READ COMMITTED each statement sees the votes committed before it began,
     * so two counts could straddle another player's re-answer and count their one vote on both sides,
     * or on neither.
     */
    private fun tally(questionId: String): VoteTallyDto {
        val tally = QuestionTally()
        return tally.of(Questions.select(tally.columns).where { Questions.id eq questionId }.single())
    }
}

/**
 * The time an answer took as the vote keeps it (CLAUDE.md §8b, *Personalization*): [sent] when it is
 * from 0 to [WyrApi.Limits.MAX_ANSWER_MILLIS], and none otherwise. Never a refusal: a player who left the
 * question on screen, or a clock that jumped, still answered.
 */
internal fun keptAnswerMillis(sent: Long?): Long? = sent?.takeIf { it in 0..WyrApi.Limits.MAX_ANSWER_MILLIS }

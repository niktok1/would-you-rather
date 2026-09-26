package io.ntole.wyr.server.report

import io.ntole.wyr.core.report.ReportReason
import io.ntole.wyr.server.db.HiddenAuthors
import io.ntole.wyr.server.db.HiddenQuestions
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Reports
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * What a player does about a question they do not want (CLAUDE.md §8d, *Reports*): report it to the
 * moderator, hide it, or hide everything its author wrote. Each hides from the player alone, for good,
 * and the feed never serves them what they hid (`QuestionStore.visibleTo`). Nothing here pays or costs
 * anybody anything, or touches the tally, the reactions or the cycle.
 *
 * Each takes only a question a player may be served ([QuestionStore.isServable]), and any other is not
 * found, as for a vote. And each resolves the player before it writes, as a vote does: a validly signed
 * token can outlive its player, and inserting first would trip a foreign key instead of answering 401.
 */
object ReportStore {
    /**
     * Records [playerId]'s report of [questionId] for [reason], already checked to be a real one, and
     * hides the question from them ([hideQuestion]). Must run inside a transaction.
     *
     * A player holds one report per question, so a report of one they reported already replaces the
     * reason and the time, and the moderator counts it once. The report held is read under its row
     * lock, so a second report of the same player's waits, then replaces what the first left. With no
     * row to lock, two first reports racing both insert, and the second fails on the key: not caught,
     * since PostgreSQL aborts a transaction at its first error, so Exposed reruns the whole transaction,
     * which finds the first's report and replaces its reason (CLAUDE.md §4).
     */
    fun report(
        playerId: String,
        questionId: String,
        reason: ReportReason,
        now: Long = System.currentTimeMillis(),
    ) {
        requireServedTo(playerId, questionId)

        val reported =
            Reports
                .select(Reports.reason)
                .where { mine(playerId, questionId) }
                .forUpdate()
                .any()
        if (reported) {
            Reports.update({ mine(playerId, questionId) }) { row ->
                row[Reports.reason] = reason
                row[reportedAt] = now
            }
        } else {
            Reports.insert { row ->
                row[Reports.playerId] = playerId
                row[Reports.questionId] = questionId
                row[Reports.reason] = reason
                row[reportedAt] = now
            }
        }

        hide(playerId, questionId)
    }

    /**
     * Hides [questionId] from [playerId] for good, without a report. Must run inside a transaction.
     * Hiding it again writes nothing.
     */
    fun hideQuestion(
        playerId: String,
        questionId: String,
    ) {
        requireServedTo(playerId, questionId)
        hide(playerId, questionId)
    }

    /**
     * Hides every question by the author of [questionId] from [playerId] for good, those approved later
     * included. Must run inside a transaction. Hiding them again writes nothing.
     *
     * A question nobody wrote, a seed, has no author to hide, so this hides only the question itself:
     * the player asked not to see it, and cannot tell a seed from any other question, so a refusal would
     * only be a failure they could do nothing about (provisional, CLAUDE.md §8b). An author may hide
     * their own questions from themselves, as they may answer them.
     *
     * The author is a plain read, as a like's is (`ReactionStore`): nothing changes who wrote a question.
     */
    fun hideAuthorOf(
        playerId: String,
        questionId: String,
    ) {
        requireServedTo(playerId, questionId)

        val author =
            Questions
                .select(Questions.authorPlayerId)
                .where { Questions.id eq questionId }
                .single()[Questions.authorPlayerId]
        if (author == null) {
            hide(playerId, questionId)
            return
        }

        val hidden =
            HiddenAuthors
                .select(HiddenAuthors.authorPlayerId)
                .where { (HiddenAuthors.playerId eq playerId) and (HiddenAuthors.authorPlayerId eq author) }
                .any()
        if (!hidden) {
            HiddenAuthors.insert { row ->
                row[HiddenAuthors.playerId] = playerId
                row[authorPlayerId] = author
            }
        }
    }

    /**
     * Hides [questionId] from [playerId], unless it is hidden already. The read only spares a certain
     * key violation: two racing both insert, and the second fails on the key and is rerun, as for a
     * report, and then finds the row (CLAUDE.md §4).
     */
    private fun hide(
        playerId: String,
        questionId: String,
    ) {
        val hidden =
            HiddenQuestions
                .select(HiddenQuestions.questionId)
                .where { (HiddenQuestions.playerId eq playerId) and (HiddenQuestions.questionId eq questionId) }
                .any()
        if (hidden) return

        HiddenQuestions.insert { row ->
            row[HiddenQuestions.playerId] = playerId
            row[HiddenQuestions.questionId] = questionId
        }
    }

    /** Refuses a question [playerId] may not be served with 404, and a player who is gone with 401. */
    private fun requireServedTo(
        playerId: String,
        questionId: String,
    ) {
        if (!QuestionStore.isServable(questionId)) throw ApiFailure.questionNotFound(questionId)
        if (PlayerStore.find(playerId) == null) throw ApiFailure.unauthorized("unknown player")
    }

    /** The row of [playerId]'s report of [questionId], if they made one. */
    private fun mine(
        playerId: String,
        questionId: String,
    ) = (Reports.playerId eq playerId) and (Reports.questionId eq questionId)
}

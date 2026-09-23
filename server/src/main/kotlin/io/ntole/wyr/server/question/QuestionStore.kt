package io.ntole.wyr.server.question

import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Votes
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Random
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

object QuestionStore {
    /**
     * The next batch of at most [limit] questions for [playerId] (CLAUDE.md §8d). Must run inside a
     * transaction.
     *
     * Questions the player has not answered come first, in random order. When fewer than [limit]
     * remain, the batch is topped up with answered ones, least recently answered first and marked
     * [QuestionDto.answeredBefore], so the feed loops instead of running dry: a batch is empty only
     * when nothing in [category] is [servableTo] the player at all.
     *
     * One statement, not an unanswered query then an answered one. The left join finds at most one
     * vote per question (the Votes key is player and question), so no question appears twice. And
     * at READ COMMITTED one statement reads one snapshot, where two could straddle a vote the player
     * commits in between, and serve that question once as unanswered and once as answered.
     *
     * `answered_at` sorts the unanswered (no vote, so null) first, and then answered ones oldest
     * first. The random key only breaks ties: it shuffles all of the unanswered, and answers given
     * in the same millisecond. Exposed renders it as `RANDOM()`, which H2 and PostgreSQL both have.
     */
    fun feed(
        playerId: String,
        limit: Int,
        category: QuestionCategory?,
    ): QuestionPageDto {
        val rows =
            Questions
                .join(Votes, JoinType.LEFT, Questions.id, Votes.questionId) { Votes.playerId eq playerId }
                .select(Questions.columns + Votes.answeredAt)
                .where { servableTo(playerId) and inCategory(category) }
                .orderBy(Votes.answeredAt to SortOrder.ASC_NULLS_FIRST, Random() to SortOrder.ASC)
                .limit(limit)
                .toList()

        return QuestionPageDto(
            questions = rows.map { row -> toDto(row, answeredBefore = row.getOrNull(Votes.answeredAt) != null) },
        )
    }

    /**
     * Which questions [playerId] may be served at all, whether answered or not. This is the one
     * place that decides it, so the feed and anything that counts what a player has left agree.
     *
     * Today that is every question. The exclusions CLAUDE.md §8d plans, an author's own questions
     * and submissions a moderator has not approved, belong here.
     */
    internal fun servableTo(playerId: String): Op<Boolean> = Op.TRUE

    private fun inCategory(category: QuestionCategory?): Op<Boolean> =
        category?.let { wanted -> Questions.category eq wanted.name } ?: Op.TRUE

    fun exists(id: String): Boolean =
        Questions
            .selectAll()
            .where { Questions.id eq id }
            .limit(1)
            .any()

    private fun toDto(
        row: ResultRow,
        answeredBefore: Boolean,
    ): QuestionDto =
        QuestionDto(
            id = row[Questions.id],
            optionA = row[Questions.optionA],
            optionB = row[Questions.optionB],
            // A category written by an older/newer build than this one still has to read back.
            category =
                runCatching { QuestionCategory.valueOf(row[Questions.category]) }
                    .getOrDefault(QuestionCategory.RANDOM),
            answeredBefore = answeredBefore,
        )
}

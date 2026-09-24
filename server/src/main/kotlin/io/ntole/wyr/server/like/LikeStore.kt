package io.ntole.wyr.server.like

import io.ntole.wyr.core.like.LikeResultDto
import io.ntole.wyr.server.db.Likes
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.QuestionStore
import io.ntole.wyr.server.vote.Scoring
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select

object LikeStore {
    /** How many players like a question, and whether the player asking is one of them. */
    data class QuestionLikes(
        val count: Int,
        val likedByMe: Boolean,
    ) {
        companion object {
            val NONE = QuestionLikes(count = 0, likedByMe = false)
        }
    }

    /**
     * Sets whether [playerId] likes [questionId] (CLAUDE.md §8d) and returns where the question's
     * likes then stand. Must run inside a transaction.
     *
     * A set, not a toggle: [liked] is what the player is to hold, and asking for what already holds
     * writes nothing and pays nothing, so a retry is harmless without an attempt id. Each like held
     * is [Scoring.POINTS_PER_LIKE] to the question's author, paid in the transaction that adds the
     * like and taken back in the one that removes it. Only a like actually added pays and only one
     * actually removed takes back, so an author's total is always what their answers earned plus a
     * point for each like their questions hold. A seed has no author: its likes count and pay
     * nobody. An author may like their own question, and is paid for it like for anyone's like.
     *
     * Any question the player may be served can be liked, answered or not, and no other: one a
     * moderator has not approved, or has retired, is not found, as for a vote
     * ([QuestionStore.lockIfServable]), and so is an unlike of it. A retired question's likes stay
     * held, and so stay paid, until it is restored (CLAUDE.md §8d, *Moderation*). A servable one stays
     * locked until this transaction ends, so a retirement and a like of one question never cross. A
     * like does nothing else. It is no answer and no skip, so it pays the liker nothing and leaves
     * the tally, the cycle and what is due alone.
     *
     * The player is resolved before the write, as for a vote: a validly signed token can outlive its
     * player, and inserting first would trip the Likes foreign key instead of answering 401.
     */
    fun setLiked(
        playerId: String,
        questionId: String,
        liked: Boolean,
    ): LikeResultDto {
        if (!QuestionStore.lockIfServable(questionId)) throw ApiFailure.questionNotFound(questionId)

        if (PlayerStore.find(playerId) == null) throw ApiFailure.unauthorized("unknown player")

        if (liked) {
            if (addLike(playerId, questionId)) payAuthorOf(questionId, Scoring.POINTS_PER_LIKE)
        } else {
            if (removeLike(playerId, questionId)) payAuthorOf(questionId, -Scoring.POINTS_PER_LIKE)
        }

        val likes = likesOf(playerId, listOf(questionId))[questionId] ?: QuestionLikes.NONE
        return LikeResultDto(questionId = questionId, likeCount = likes.count, likedByMe = likes.likedByMe)
    }

    /**
     * Adds the like unless the player holds it already, and says whether it did.
     *
     * A plain read and then an insert, with no lock of the like's own, because the read never decides
     * a write the key does not guard (CLAUDE.md §4). Finding the like, this writes nothing, and
     * nothing is paid for a like that added nothing. Finding none, it inserts, and the key decides
     * whether that is the first like. Two first likes of one question do not race on it today: the
     * second waits on the question's lock ([QuestionStore.lockIfServable]), then finds the first's
     * committed like and adds nothing, so the author is paid once. Were they to race, the second
     * would fail on the key (SQLState 23505), which is deliberately not caught, since PostgreSQL
     * aborts a transaction at its first error: Exposed would roll back and rerun the whole
     * transaction, which would find the committed like.
     */
    private fun addLike(
        playerId: String,
        questionId: String,
    ): Boolean {
        val held =
            Likes
                .select(Likes.playerId)
                .where { (Likes.playerId eq playerId) and (Likes.questionId eq questionId) }
                .limit(1)
                .any()
        if (held) return false

        Likes.insert { row ->
            row[Likes.playerId] = playerId
            row[Likes.questionId] = questionId
        }
        return true
    }

    /**
     * Removes the like if the player holds it, and says whether it did. One statement, with no read
     * before it to go stale: of two unlikes, the second, once past the question's lock, finds the row
     * gone, deletes nothing and takes nothing back.
     */
    private fun removeLike(
        playerId: String,
        questionId: String,
    ): Boolean = Likes.deleteWhere { (Likes.playerId eq playerId) and (Likes.questionId eq questionId) } == 1

    /**
     * Pays the question's author [points], or takes them back when negative. A seed has no author,
     * and pays nobody.
     *
     * The author is a plain read, safe because nothing ever changes who wrote a question. The points
     * are an SQL increment ([PlayerStore.addPoints]), so a burst of likes for one author all count.
     */
    private fun payAuthorOf(
        questionId: String,
        points: Int,
    ) {
        val author =
            Questions
                .select(Questions.authorPlayerId)
                .where { Questions.id eq questionId }
                .single()[Questions.authorPlayerId]
                ?: return
        PlayerStore.addPoints(playerId = author, points = points)
    }

    /**
     * The likes on each of [questionIds] as [playerId] sees them, by question id: how many players
     * like it, and whether [playerId] is one. A question nobody likes is left out. Must run inside a
     * transaction.
     *
     * One statement for all of them, however many questions, rather than one per question. And one
     * for both numbers, so they agree: at READ COMMITTED two statements could straddle the player's
     * own unlike committing in between, and report the question liked by them and by nobody
     * (CLAUDE.md §4).
     */
    internal fun likesOf(
        playerId: String,
        questionIds: List<String>,
    ): Map<String, QuestionLikes> {
        if (questionIds.isEmpty()) return emptyMap()

        val likes = Likes.playerId.count()
        // COUNT skips a null, and the CASE is null for every like but the player's own.
        val mine = Count(case().When(Likes.playerId eq playerId, intLiteral(1)))
        return Likes
            .select(Likes.questionId, likes, mine)
            .where { Likes.questionId inList questionIds }
            .groupBy(Likes.questionId)
            .associate { row ->
                row[Likes.questionId] to QuestionLikes(count = row[likes].toInt(), likedByMe = row[mine] > 0)
            }
    }

    /**
     * How many likes the questions [authorId] submitted hold now, their own likes included: each is
     * a point in their total, paid and not taken back. A retired question's likes count as well: they
     * stay held, and paid (CLAUDE.md §8c). A seed has no author, so its likes are
     * nobody's. An expression to embed in a larger statement (`StatsStore`), so it is read at the
     * same moment as the total those likes paid into.
     */
    internal fun receivedBy(authorId: String): Expression<Long?> =
        wrapAsExpression(
            Likes
                .join(Questions, JoinType.INNER, Likes.questionId, Questions.id)
                .select(Likes.playerId.count())
                .where { Questions.authorPlayerId eq authorId },
        )
}

package io.ntole.wyr.server.reaction

import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.core.reaction.ReactionResultDto
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Reactions
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
import org.jetbrains.exposed.v1.jdbc.update

object ReactionStore {
    /** How many players like a question and dislike it, and what the player asking thinks of it. */
    data class QuestionReactions(
        val likes: Int,
        val dislikes: Int,
        val mine: Reaction,
    ) {
        companion object {
            val NONE = QuestionReactions(likes = 0, dislikes = 0, mine = Reaction.NONE)
        }
    }

    /**
     * Sets what [playerId] thinks of [questionId] (CLAUDE.md §8d, *Reactions*) and returns where the
     * question's reactions then stand. Must run inside a transaction.
     *
     * A set, not a toggle: [reaction] is what the player is to hold, [Reaction.NONE] for neither, and
     * asking for what already holds writes nothing and pays nothing, so a retry is harmless without an
     * attempt id. A player holds one reaction per question, so a like replaces a dislike and a dislike
     * a like. Each like held is [Scoring.POINTS_PER_LIKE] to the question's author, paid in the
     * transaction that adds it and taken back in the one that removes it, whether taken back alone or
     * replaced by a dislike. A dislike is worth nothing to anybody: the user's decision, since a guest
     * costs nothing to mint (CLAUDE.md §8c). So an author's total is always what their answers earned
     * plus a point for each like their questions hold, less what their questions not rejected cost
     * them ([Scoring]). A seed has no author: its reactions count and pay nobody. An author may react
     * to their own question, and is paid for their own like as for anyone's (CLAUDE.md §8c).
     *
     * Any question the player may be served can be reacted to, answered or not, and no other: one a
     * moderator has not approved, or has retired, is not found, as for a vote
     * ([QuestionStore.isServable]), and so is taking a reaction back. A retired question's reactions
     * stay held, and its likes paid, until it is restored (CLAUDE.md §8d, *Moderation*). A reaction
     * does nothing else. It is no answer and no skip, so it pays the player nothing and leaves the
     * tally, the cycle and what is due alone.
     *
     * The reaction held is read under its row lock ([heldBy]), since what is written, and what is paid,
     * depends on which of the three it is (CLAUDE.md §4). A second request of the same player's for the
     * same question waits for this one to commit, then reads what it left. With no row to lock, two
     * first reactions racing both find none and both insert: the second waits on the first's
     * uncommitted key, then fails on it once the first commits (SQLState 23505). That failure is
     * deliberately not caught, since PostgreSQL aborts a transaction at its first error: Exposed rolls
     * back and reruns the whole transaction, which finds the committed reaction, so the author is paid
     * once.
     *
     * The player is resolved before the write, as for a vote: a validly signed token can outlive its
     * player, and inserting first would trip the Reactions foreign key instead of answering 401.
     */
    fun set(
        playerId: String,
        questionId: String,
        reaction: Reaction,
    ): ReactionResultDto {
        if (!QuestionStore.isServable(questionId)) throw ApiFailure.questionNotFound(questionId)

        if (PlayerStore.find(playerId) == null) throw ApiFailure.unauthorized("unknown player")

        val held = heldBy(playerId, questionId)
        if (held != reaction) {
            when {
                held == Reaction.NONE -> {
                    Reactions.insert { row ->
                        row[Reactions.playerId] = playerId
                        row[Reactions.questionId] = questionId
                        row[Reactions.reaction] = reaction
                    }
                }

                reaction == Reaction.NONE -> {
                    Reactions.deleteWhere { mine(playerId, questionId) }
                }

                else -> {
                    Reactions.update({ mine(playerId, questionId) }) { row -> row[Reactions.reaction] = reaction }
                }
            }
            val points = pointsFor(reaction) - pointsFor(held)
            if (points != 0) payAuthorOf(questionId, points)
        }

        val reactions = reactionsOf(playerId, listOf(questionId))[questionId] ?: QuestionReactions.NONE
        return ReactionResultDto(
            questionId = questionId,
            likeCount = reactions.likes,
            dislikeCount = reactions.dislikes,
            myReaction = reactions.mine,
        )
    }

    /**
     * What [playerId] thinks of [questionId] now, [Reaction.NONE] for no row, with the row locked
     * until this transaction ends when there is one.
     */
    private fun heldBy(
        playerId: String,
        questionId: String,
    ): Reaction =
        Reactions
            .select(Reactions.reaction)
            .where { mine(playerId, questionId) }
            .forUpdate()
            .singleOrNull()
            ?.get(Reactions.reaction)
            ?: Reaction.NONE

    /** The row of [playerId]'s reaction to [questionId], if they hold one. */
    private fun mine(
        playerId: String,
        questionId: String,
    ) = (Reactions.playerId eq playerId) and (Reactions.questionId eq questionId)

    /** What holding [reaction] is worth to the question's author. */
    private fun pointsFor(reaction: Reaction): Int = if (reaction == Reaction.LIKE) Scoring.POINTS_PER_LIKE else 0

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
     * The reactions to each of [questionIds] as [playerId] sees them, by question id: how many players
     * like it and dislike it, and what [playerId] thinks of it. A question nobody has reacted to is left
     * out. Must run inside a transaction.
     *
     * One statement for all of them, however many questions, rather than one per question. And one
     * for every number, so they agree: at READ COMMITTED two statements could straddle the player's
     * own change of mind committing in between, and report the question liked by them and by nobody
     * (CLAUDE.md §4).
     */
    internal fun reactionsOf(
        playerId: String,
        questionIds: List<String>,
    ): Map<String, QuestionReactions> {
        if (questionIds.isEmpty()) return emptyMap()

        // COUNT skips a null, and each CASE is null for every row but those it names.
        val likes = Count(case().When(Reactions.reaction eq Reaction.LIKE, intLiteral(1)))
        val dislikes = Count(case().When(Reactions.reaction eq Reaction.DISLIKE, intLiteral(1)))
        val myLikes = Count(case().When(isMine(playerId, Reaction.LIKE), intLiteral(1)))
        val myDislikes = Count(case().When(isMine(playerId, Reaction.DISLIKE), intLiteral(1)))
        return Reactions
            .select(Reactions.questionId, likes, dislikes, myLikes, myDislikes)
            .where { Reactions.questionId inList questionIds }
            .groupBy(Reactions.questionId)
            .associate { row ->
                val mine =
                    when {
                        row[myLikes] > 0 -> Reaction.LIKE
                        row[myDislikes] > 0 -> Reaction.DISLIKE
                        else -> Reaction.NONE
                    }
                row[Reactions.questionId] to
                    QuestionReactions(likes = row[likes].toInt(), dislikes = row[dislikes].toInt(), mine = mine)
            }
    }

    /** [playerId]'s own [reaction], in a row of any player's. */
    private fun isMine(
        playerId: String,
        reaction: Reaction,
    ) = (Reactions.playerId eq playerId) and (Reactions.reaction eq reaction)

    /**
     * How many players hold [reaction] on the question of the row a statement reads: a subquery on
     * [Questions.id], found through `reactions (question_id, player_id)`, to embed beside a question's
     * own columns so its numbers are one moment's (the moderator's list, `ModerationStore.questions`,
     * and a submission, `SubmissionStore`). Each call makes a new expression, and a row is read back by
     * the instance its statement selected.
     */
    internal fun countOn(reaction: Reaction): Expression<Long?> =
        wrapAsExpression(
            Reactions
                .select(Reactions.playerId.count())
                .where { (Reactions.questionId eq Questions.id) and (Reactions.reaction eq reaction) },
        )

    /**
     * How many likes the questions [authorId] submitted hold now, their own likes included: each is
     * a point in their total, paid and not taken back. A retired question's likes count as well: they
     * stay held, and paid (CLAUDE.md §8c). A seed has no author, so its likes are nobody's. Dislikes
     * are worth nothing, so they are not counted. An expression to embed in a larger statement
     * (`StatsStore`), so it is read at the same moment as the total those likes paid into.
     */
    internal fun likesReceivedBy(authorId: String): Expression<Long?> =
        wrapAsExpression(
            Reactions
                .join(Questions, JoinType.INNER, Reactions.questionId, Questions.id)
                .select(Reactions.playerId.count())
                .where { (Questions.authorPlayerId eq authorId) and (Reactions.reaction eq Reaction.LIKE) },
        )
}

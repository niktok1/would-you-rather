package io.ntole.wyr.server.player

import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.server.db.HiddenAuthors
import io.ntole.wyr.server.db.HiddenQuestions
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.QuestionCategories
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Reactions
import io.ntole.wyr.server.db.Reports
import io.ntole.wyr.server.db.Sessions
import io.ntole.wyr.server.db.Skips
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.vote.Scoring
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * A player deleting their account (CLAUDE.md §8a, *Deleting an account*), which Google Play asks a
 * game with accounts to offer. Everything that is theirs goes, and nothing another player earned from
 * it stays unaccounted for.
 */
object AccountDeletion {
    /**
     * Deletes [playerId], in one transaction, which it must run inside: their username and password
     * hash with their row, their sessions, votes, skips, reactions, reports and what they hid, their
     * push tokens, Play Games links and purchases, which their keys' cascade takes with the row, and their
     * questions that no player is served, pending and rejected. Their approved questions stay, retired
     * ones included, with nobody as their author, as a seed has: served as before, their votes and
     * reactions kept, their likes from then on paying nobody. A player who is gone already is 401, as
     * any request for them is.
     *
     * Each like the player holds is taken back as taking it back would: a point from the author of the
     * question it is on, their own questions' authors, themselves, aside (CLAUDE.md §8c). So every other
     * author's total is still what their answers earned, plus a like for each like their questions
     * hold, less what their questions cost. A player who hid the deleted author keeps each of the
     * author's approved questions hidden, one by one, since the author is no longer there to hide.
     *
     * The player's row is locked first, so nothing of theirs can be added meanwhile (CLAUDE.md §4): a
     * vote, skip, reaction, report, hide, session or submission of theirs, or a hide of them as an
     * author, inserts a row whose foreign key waits on that lock and fails once this commits, and its
     * rerun finds them gone. Their reactions are locked before they are counted, so a change of mind in
     * flight either commits first and is counted or waits and finds its row gone. Their unapproved
     * questions are locked too, so a moderator's decision on one waits and then finds it gone.
     */
    fun delete(playerId: String) {
        Players
            .select(Players.id)
            .where { Players.id eq playerId }
            .forUpdate()
            .singleOrNull()
            ?: throw ApiFailure.unauthorized("unknown player")

        takeBackLikes(playerId)
        Reactions.deleteWhere { Reactions.playerId eq playerId }
        Votes.deleteWhere { Votes.playerId eq playerId }
        Skips.deleteWhere { Skips.playerId eq playerId }
        Reports.deleteWhere { Reports.playerId eq playerId }
        HiddenQuestions.deleteWhere { HiddenQuestions.playerId eq playerId }
        HiddenAuthors.deleteWhere { HiddenAuthors.playerId eq playerId }

        deleteUnservedQuestions(playerId)
        keepHiddenFromThoseWhoHidThem(playerId)
        HiddenAuthors.deleteWhere { HiddenAuthors.authorPlayerId eq playerId }
        Questions.update({ Questions.authorPlayerId eq playerId }) { row -> row[authorPlayerId] = null }

        Sessions.deleteWhere { Sessions.playerId eq playerId }
        Players.deleteWhere { Players.id eq playerId }
    }

    /**
     * Takes back what each like [playerId] holds paid, a point from each author per like on their
     * questions, the player's own and seeds, which paid nobody, aside. The reactions are locked first,
     * all of them, since a dislike turned into a like in flight would pay an author a point this did not
     * count; PostgreSQL takes no lock on a grouped read, so the lock is a statement of its own.
     */
    private fun takeBackLikes(playerId: String) {
        Reactions
            .select(Reactions.questionId)
            .where { Reactions.playerId eq playerId }
            .forUpdate()
            .toList()

        val likes = Reactions.questionId.count()
        Reactions
            .join(Questions, JoinType.INNER, Reactions.questionId, Questions.id)
            .select(Questions.authorPlayerId, likes)
            .where {
                (Reactions.playerId eq playerId) and
                    (Reactions.reaction eq Reaction.LIKE) and
                    Questions.authorPlayerId.isNotNull() and
                    (Questions.authorPlayerId neq playerId)
            }.groupBy(Questions.authorPlayerId)
            .toList()
            .forEach { row ->
                val author = checkNotNull(row[Questions.authorPlayerId]) { "a grouped author came back null" }
                PlayerStore.payAuthor(author, points = -Scoring.POINTS_PER_LIKE * row[likes].toInt())
            }
    }

    /**
     * Deletes [playerId]'s questions that are not approved, pending or rejected, with their categories.
     * No player was ever served one, so nothing else names them: no vote, skip, reaction, report or
     * hide. Locked first, so no decision comes between.
     */
    private fun deleteUnservedQuestions(playerId: String) {
        val unserved = (Questions.authorPlayerId eq playerId) and (Questions.status neq QuestionStatus.APPROVED)
        Questions
            .select(Questions.id)
            .where { unserved }
            .forUpdate()
            .toList()

        QuestionCategories.deleteWhere {
            QuestionCategories.questionId inSubQuery Questions.select(Questions.id).where { unserved }
        }
        Questions.deleteWhere { unserved }
    }

    /**
     * Hides each approved question of [playerId]'s, one by one, from each player who hid them as an
     * author, where it is not hidden already: once the author is nobody, a hide of the author would
     * hide nothing. One statement for all of them.
     */
    private fun keepHiddenFromThoseWhoHidThem(playerId: String) {
        val alreadyHidden =
            HiddenQuestions
                .select(HiddenQuestions.questionId)
                .where {
                    (HiddenQuestions.playerId eq HiddenAuthors.playerId) and
                        (HiddenQuestions.questionId eq Questions.id)
                }
        HiddenQuestions.insert(
            HiddenAuthors
                .join(Questions, JoinType.INNER, HiddenAuthors.authorPlayerId, Questions.authorPlayerId)
                .select(HiddenAuthors.playerId, Questions.id)
                .where { (HiddenAuthors.authorPlayerId eq playerId) and notExists(alreadyHidden) },
            columns = listOf(HiddenQuestions.playerId, HiddenQuestions.questionId),
        )
    }
}

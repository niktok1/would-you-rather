package io.ntole.wyr.play

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayScreenTest {
    @Test
    fun `a vote shows the points the server awarded it`() {
        assertEquals("+1", pointsThisVote(outcome(pointsAwarded = 1, replayed = false)))
    }

    @Test
    fun `a replayed vote shows no points of its own`() {
        // The Play tab's retry of a vote whose response was lost: it landed and paid the first time.
        assertNull(pointsThisVote(outcome(pointsAwarded = 0, replayed = true)))
    }

    @Test
    fun `a question shows how many like it`() {
        assertEquals("0 likes", likeCountOf(QUESTION))
        assertEquals("1 like", likeCountOf(QUESTION.copy(likeCount = 1, likedByMe = true)))
        assertEquals("12 likes", likeCountOf(QUESTION.copy(likeCount = 12)))
    }

    @Test
    fun `the Like button unlikes a question the player likes and likes any other`() {
        assertEquals("Unlike", likeActionOf(QUESTION.copy(likeCount = 1, likedByMe = true)))
        // Liked by others only: the player's own like is what the button sets.
        assertEquals("Like", likeActionOf(QUESTION.copy(likeCount = 4, likedByMe = false)))
    }

    @Test
    fun `a failed like says what went wrong in the player's words`() {
        assertEquals("Can't reach the game right now. Try again.", likeFailureMessage(DomainError.NETWORK))
        // A retired question is 404 to like and to unlike alike.
        assertEquals("That question is no longer in the game.", likeFailureMessage(DomainError.QUESTION_NOT_FOUND))
        assertEquals("Something went wrong. Try again.", likeFailureMessage(DomainError.SERVER))
    }

    private fun outcome(
        pointsAwarded: Int,
        replayed: Boolean,
    ): VoteOutcome =
        VoteOutcome(
            questionId = "q1",
            yourSide = Side.A,
            tally = Tally(votesA = 1, votesB = 0),
            pointsAwarded = pointsAwarded,
            totalPoints = 1,
            replayed = replayed,
        )

    private companion object {
        val QUESTION = Question(id = "q1", optionA = "Fly", optionB = "Swim", categories = setOf(Category.FOOD))
    }
}

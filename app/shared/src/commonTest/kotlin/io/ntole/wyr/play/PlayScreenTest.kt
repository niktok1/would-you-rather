package io.ntole.wyr.play

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `no category selected is all of them`() {
        assertEquals("All", categoriesPlayed(PlayedCategories(known = KNOWN)))
    }

    @Test
    fun `the categories played are named in Serbian in the order the server lists them`() {
        // Not in the order the set holds them: in the server's, as the picker lists them.
        assertEquals("Храна, Етика", categoriesPlayed(PlayedCategories(linkedSetOf("ETHICS", "FOOD"), KNOWN)))
        assertEquals("Апсурдно", categoriesPlayed(PlayedCategories(setOf("ABSURD"), KNOWN)))
    }

    @Test
    fun `a category not read yet is named by its id after the rest`() {
        // Added after the list was read, or played before any read landed.
        assertEquals("Храна, ANIMALS", categoriesPlayed(PlayedCategories(linkedSetOf("ANIMALS", "FOOD"), KNOWN)))
        assertEquals("FOOD", categoriesPlayed(PlayedCategories(setOf("FOOD"))))
        assertEquals("FOOD", categoryName("FOOD", known = emptyList()))
    }

    @Test
    fun `every category selected is named and not called All`() {
        // Not none: a category a moderator adds later is in none, and not in these.
        assertEquals("Храна, Етика, Апсурдно", categoriesPlayed(PlayedCategories(KNOWN.map { it.id }.toSet(), KNOWN)))
    }

    @Test
    fun `the picker says it is reading the categories only while it has none to list`() {
        assertEquals(
            "Loading the categories…",
            pickerNote(CategoryPicking(emptySet(), isLoading = true), listed = false),
        )
        assertNull(pickerNote(CategoryPicking(emptySet(), isLoading = true), listed = true))
        assertNull(pickerNote(CategoryPicking(emptySet()), listed = true))
    }

    @Test
    fun `a picker whose read failed says so in the player's words`() {
        assertEquals(
            "Can't reach the game to list the categories.",
            pickerNote(CategoryPicking(emptySet(), failure = DomainError.NETWORK), listed = true),
        )
        assertEquals(
            "Couldn't list the categories. Open this again to retry.",
            pickerNote(CategoryPicking(emptySet(), failure = DomainError.SERVER), listed = false),
        )
    }

    @Test
    fun `the categories change only while nothing is loading or in flight`() {
        assertFalse(PlayUiState.Loading.canChangeCategories)
        assertTrue(PlayUiState.Asking(QUESTION).canChangeCategories)
        assertFalse(PlayUiState.Asking(QUESTION, isSubmitting = true).canChangeCategories)
        assertFalse(PlayUiState.Asking(QUESTION, isLiking = true).canChangeCategories)
        val revealed = PlayUiState.Revealed(QUESTION, outcome(pointsAwarded = 1, replayed = false))
        assertTrue(revealed.canChangeCategories)
        assertFalse(revealed.copy(isLiking = true).canChangeCategories)
        // Where a selection with nothing to serve leaves the player.
        assertTrue(PlayUiState.Failed(DomainError.OUT_OF_QUESTIONS).canChangeCategories)
    }

    private fun outcome(
        pointsAwarded: Int,
        replayed: Boolean,
    ): VoteOutcome =
        VoteOutcome(
            yourSide = Side.A,
            tally = Tally(votesA = 1, votesB = 0),
            pointsAwarded = pointsAwarded,
            totalPoints = 1,
            replayed = replayed,
        )

    private companion object {
        val QUESTION = Question(id = "q1", optionA = "Fly", optionB = "Swim", categories = setOf("FOOD"))

        /** As the server lists them: oldest first. */
        val KNOWN =
            listOf(
                Category(id = "FOOD", nameSr = "Храна", nameEn = "Food"),
                Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics"),
                Category(id = "ABSURD", nameSr = "Апсурдно", nameEn = "Absurd"),
            )
    }
}

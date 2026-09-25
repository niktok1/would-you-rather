package io.ntole.wyr.play

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.language.EnglishStrings
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.SerbianCyrillicStrings
import io.ntole.wyr.language.SerbianLatinStrings
import io.ntole.wyr.language.stringsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayScreenTest {
    @Test
    fun `the points are the number and its unit never broken between them`() {
        assertEquals("123 П", SerbianCyrillicStrings.playScreen.points(123))
        assertEquals("123 P", SerbianLatinStrings.playScreen.points(123))
        assertEquals("0 P", EnglishStrings.playScreen.points(0))
    }

    @Test
    fun `a percentage is the number and its sign`() {
        Language.entries.forEach { language -> assertEquals("70%", stringsOf(language).playScreen.percent(70)) }
    }

    @Test
    fun `each failure is worded in the language shown`() {
        val strings = SerbianCyrillicStrings.playScreen
        assertEquals(strings.noInternet, failureText(DomainError.NETWORK, strings))
        assertEquals(strings.outOfQuestions, failureText(DomainError.OUT_OF_QUESTIONS, strings))
        assertEquals(strings.slowDown, failureText(DomainError.RATE_LIMITED, strings))
        // A retired question is 404 to answer and to like alike.
        assertEquals(strings.questionGone, failureText(DomainError.QUESTION_NOT_FOUND, strings))
        assertEquals("That question is gone.", failureText(DomainError.QUESTION_NOT_FOUND, EnglishStrings.playScreen))
    }

    /** The Play screen never submits, moderates or logs in, so those have the one short sentence. */
    @Test
    fun `every other failure is the same short sentence`() {
        val strings = EnglishStrings.playScreen
        val worded =
            listOf(
                DomainError.NETWORK,
                DomainError.OUT_OF_QUESTIONS,
                DomainError.RATE_LIMITED,
                DomainError.QUESTION_NOT_FOUND,
            )
        DomainError.entries.filter { it !in worded }.forEach { error ->
            assertEquals(strings.somethingWrong, failureText(error, strings), "$error")
        }
    }

    @Test
    fun `no category selected is all of them in the language shown`() {
        assertEquals("Све", categoriesPlayed(emptySet(), all = SerbianCyrillicStrings.playScreen.allCategories))
        assertEquals("All", categoriesPlayed(emptySet(), all = EnglishStrings.playScreen.allCategories))
    }

    @Test
    fun `the categories played are named in the picker's order`() {
        // Not in the order the set holds them: in declaration order, as the picker lists them.
        assertEquals("Food, Ethics", categoriesPlayed(linkedSetOf(Category.ETHICS, Category.FOOD), all = ALL))
        assertEquals("Superpowers", categoriesPlayed(setOf(Category.SUPERPOWERS), all = ALL))
    }

    @Test
    fun `every category selected is named and not called All`() {
        // Not none: a question filed only under categories this build cannot name is in none of them.
        assertEquals(
            "Food, Lifestyle, Ethics, Superpowers, Random",
            categoriesPlayed(Category.selectable.toSet(), all = ALL),
        )
    }

    @Test
    fun `every category has a name in the player's words`() {
        assertEquals(
            listOf("Food", "Lifestyle", "Ethics", "Superpowers", "Random", "Other"),
            Category.entries.map(::categoryName),
        )
    }

    @Test
    fun `the categories change only while nothing is loading or in flight`() {
        assertFalse(PlayUiState.Loading.canChangeCategories)
        assertTrue(PlayUiState.Asking(QUESTION).canChangeCategories)
        assertFalse(PlayUiState.Asking(QUESTION, isSubmitting = true).canChangeCategories)
        assertFalse(PlayUiState.Asking(QUESTION, isLiking = true).canChangeCategories)
        val revealed = PlayUiState.Revealed(QUESTION, OUTCOME)
        assertTrue(revealed.canChangeCategories)
        assertFalse(revealed.copy(isLiking = true).canChangeCategories)
        // Where a selection with nothing to serve leaves the player.
        assertTrue(PlayUiState.Failed(DomainError.OUT_OF_QUESTIONS).canChangeCategories)
    }

    private companion object {
        const val ALL = "All"

        val QUESTION = Question(id = "q1", optionA = "Fly", optionB = "Swim", categories = setOf(Category.FOOD))

        val OUTCOME =
            VoteOutcome(
                yourSide = Side.A,
                tally = Tally(votesA = 1, votesB = 0),
                pointsAwarded = 1,
                totalPoints = 1,
            )
    }
}

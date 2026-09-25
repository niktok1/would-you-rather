package io.ntole.wyr.play

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.language.EnglishStrings
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.SerbianCyrillicStrings
import io.ntole.wyr.language.stringsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayScreenTest {
    @Test
    fun `a percentage is the number and its sign`() {
        Language.entries.forEach { language -> assertEquals("70%", stringsOf(language).playScreen.percent(70)) }
    }

    @Test
    fun `each failure is worded in the language shown`() {
        val strings = SerbianCyrillicStrings.playScreen
        assertEquals(strings.cannotReach, failureText(DomainError.NETWORK, strings))
        assertEquals(strings.outOfQuestions, failureText(DomainError.OUT_OF_QUESTIONS, strings))
        assertEquals(strings.slowDown, failureText(DomainError.RATE_LIMITED, strings))
        // A retired question is 404 to answer and to like alike.
        assertEquals(strings.questionGone, failureText(DomainError.QUESTION_NOT_FOUND, strings))
        assertEquals("That question is gone.", failureText(DomainError.QUESTION_NOT_FOUND, EnglishStrings.playScreen))
        // Offline, or the server down or past the request timeout: runApi calls them all NETWORK.
        assertEquals("Игра није доступна.", failureText(DomainError.NETWORK, strings))
        assertEquals("Can't reach the game.", failureText(DomainError.NETWORK, EnglishStrings.playScreen))
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
        val none = PlayedCategories(known = KNOWN)
        assertEquals("Све", categoriesPlayed(none, SerbianCyrillicStrings.playScreen.allCategories, CYRILLIC))
        assertEquals("All", categoriesPlayed(none, EnglishStrings.playScreen.allCategories, Language.ENGLISH))
    }

    @Test
    fun `the categories played are named in the language shown in the order the server lists them`() {
        // Not in the order the set holds them: in the server's, as the picker lists them.
        val played = PlayedCategories(linkedSetOf("ETHICS", "FOOD"), KNOWN)
        assertEquals("Храна, Етика", categoriesPlayed(played, ALL, CYRILLIC))
        assertEquals("Hrana, Etika", categoriesPlayed(played, ALL, Language.SERBIAN_LATIN))
        assertEquals("Food, Ethics", categoriesPlayed(played, ALL, Language.ENGLISH))
        assertEquals("Апсурдно", categoriesPlayed(PlayedCategories(setOf("ABSURD"), KNOWN), ALL, CYRILLIC))
    }

    @Test
    fun `a category not read yet is named by its id after the rest`() {
        // Added after the list was read, or played before any read landed.
        val added = PlayedCategories(linkedSetOf("ANIMALS", "FOOD"), KNOWN)
        assertEquals("Храна, ANIMALS", categoriesPlayed(added, ALL, CYRILLIC))
        Language.entries.forEach { language ->
            assertEquals("FOOD", categoriesPlayed(PlayedCategories(setOf("FOOD")), ALL, language))
        }
    }

    @Test
    fun `every category selected is named and not called All`() {
        // Not none: a category a moderator adds later is in none, and not in these.
        val every = PlayedCategories(KNOWN.map { it.id }.toSet(), KNOWN)
        assertEquals("Храна, Етика, Апсурдно", categoriesPlayed(every, ALL, CYRILLIC))
        assertEquals("Food, Ethics, Absurd", categoriesPlayed(every, ALL, Language.ENGLISH))
    }

    @Test
    fun `the picker says it is reading the categories only while it has none to list`() {
        val loading = CategoryPicking(emptySet(), isLoading = true)
        assertEquals("Учитавање категорија…", pickerNote(loading, listed = false, SerbianCyrillicStrings))
        assertEquals("Loading categories…", pickerNote(loading, listed = false, EnglishStrings))
        assertNull(pickerNote(loading, listed = true, EnglishStrings))
        assertNull(pickerNote(CategoryPicking(emptySet()), listed = true, EnglishStrings))
    }

    @Test
    fun `a picker whose read failed says so in one line in every language`() {
        Language.entries.map(::stringsOf).forEach { strings ->
            val offline = CategoryPicking(emptySet(), failure = DomainError.NETWORK)
            assertEquals(strings.playScreen.cannotReach, pickerNote(offline, listed = true, strings))
            val failed = CategoryPicking(emptySet(), failure = DomainError.SERVER)
            assertEquals(strings.categoriesUnread, pickerNote(failed, listed = false, strings))
        }
        val failed = CategoryPicking(emptySet(), failure = DomainError.SERVER)
        assertEquals("Категорије нису учитане.", pickerNote(failed, listed = true, SerbianCyrillicStrings))
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

        val CYRILLIC = Language.SERBIAN_CYRILLIC

        val QUESTION = Question(id = "q1", optionA = "Fly", optionB = "Swim", categories = setOf("FOOD"))

        /** As the server lists them: oldest first. */
        val KNOWN =
            listOf(
                Category(id = "FOOD", nameSr = "Храна", nameEn = "Food"),
                Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics"),
                Category(id = "ABSURD", nameSr = "Апсурдно", nameEn = "Absurd"),
            )

        val OUTCOME =
            VoteOutcome(
                yourSide = Side.A,
                tally = Tally(votesA = 1, votesB = 0),
                pointsAwarded = 1,
                totalPoints = 1,
            )
    }
}

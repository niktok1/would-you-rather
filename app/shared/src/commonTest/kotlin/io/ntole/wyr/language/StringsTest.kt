package io.ntole.wyr.language

import io.ntole.wyr.core.domain.language.SerbianScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The game's words in each language (CLAUDE.md §8f). [Strings] is a data class, so its `toString`
 * holds every text by name: comparing those checks every text, the ones added later included,
 * without a list of them here to forget one from.
 */
class StringsTest {
    @Test
    fun `Serbian Latin is the Serbian Cyrillic transliterated text for text`() {
        assertEquals(SerbianScript.toLatin(SerbianCyrillicStrings.toString()), SerbianLatinStrings.toString())
    }

    @Test
    fun `every Serbian text is written in Cyrillic`() {
        textsOf(SerbianCyrillicStrings).forEach { text ->
            assertTrue(text.any(::isCyrillic), "\"$text\" has no Cyrillic letter")
            assertFalse(text.any(::isLatin), "\"$text\" has a Latin letter")
        }
    }

    @Test
    fun `no Latin or English text has a Cyrillic letter`() {
        listOf(SerbianLatinStrings, EnglishStrings).forEach { strings ->
            assertFalse(strings.toString().any(::isCyrillic), "$strings")
        }
    }

    @Test
    fun `no text is blank in any language`() {
        Language.entries.forEach { language ->
            textsOf(stringsOf(language)).forEach { text -> assertTrue(text.isNotBlank(), "$language has a blank text") }
        }
    }

    @Test
    fun `each language has strings of its own`() {
        assertSame(SerbianCyrillicStrings, stringsOf(Language.SERBIAN_CYRILLIC))
        assertSame(SerbianLatinStrings, stringsOf(Language.SERBIAN_LATIN))
        assertSame(EnglishStrings, stringsOf(Language.ENGLISH))
        assertEquals(
            Language.entries.size,
            Language.entries
                .map(::stringsOf)
                .toSet()
                .size,
        )
    }

    /**
     * On screen the points are a coin and the number, which says nothing to a screen reader: it hears
     * a label and the number, in the language shown, and so no plural form is needed.
     */
    @Test
    fun `a screen reader hears points as a label and the number in the language shown`() {
        assertEquals("Поени: 123", SerbianCyrillicStrings.points.fill(123))
        assertEquals("Poeni: 123", SerbianLatinStrings.points.fill(123))
        assertEquals("Points: 1", EnglishStrings.points.fill(1))
    }

    /**
     * The button under a failure is one text on every screen, so the game says it one way, and the
     * failure that asks the player to try again asks in the button's words.
     */
    @Test
    fun `Try again is one text and the failure asking for it says it the same way`() {
        assertEquals("Покушај поново", SerbianCyrillicStrings.tryAgain)
        assertEquals("Pokušaj ponovo", SerbianLatinStrings.tryAgain)
        assertEquals("Try again", EnglishStrings.tryAgain)
        Language.entries.map(::stringsOf).forEach { strings ->
            val failure = strings.accountScreens.somethingWrong
            assertTrue(failure.endsWith("${strings.tryAgain}."), "\"$failure\" for \"${strings.tryAgain}\"")
        }
    }

    /**
     * A template's numbers and names go where its language puts them, but every language must have
     * each of them: a translation that dropped `{0}` would show a rate limit without its wait.
     */
    @Test
    fun `every template has the same placeholders in every language`() {
        val english = textsOf(EnglishStrings)
        Language.entries.forEach { language ->
            val texts = textsOf(stringsOf(language))
            assertEquals(english.size, texts.size, "$language")
            english.zip(texts).forEach { (source, text) ->
                assertEquals(placeholdersOf(source), placeholdersOf(text), "$language: \"$text\" for \"$source\"")
            }
        }
        assertTrue(english.any { placeholdersOf(it).isNotEmpty() }, "no template was checked")
    }

    /** Every text of [strings], as [Strings.map] visits them, which the first test holds to all of them. */
    private fun textsOf(strings: Strings): List<String> {
        val texts = mutableListOf<String>()
        strings.map { text -> text.also(texts::add) }
        return texts
    }

    private fun placeholdersOf(text: String): List<String> =
        PLACEHOLDER
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    private fun isCyrillic(char: Char): Boolean = char in 'Ѐ'..'ӿ'

    private fun isLatin(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'
}

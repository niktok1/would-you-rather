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

    /** Every text of [strings], as [Strings.map] visits them, which the first test holds to all of them. */
    private fun textsOf(strings: Strings): List<String> {
        val texts = mutableListOf<String>()
        strings.map { text -> text.also(texts::add) }
        return texts
    }

    private fun isCyrillic(char: Char): Boolean = char in 'Ѐ'..'ӿ'

    private fun isLatin(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'
}

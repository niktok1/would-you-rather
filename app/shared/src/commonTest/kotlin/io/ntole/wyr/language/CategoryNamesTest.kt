package io.ntole.wyr.language

import io.ntole.wyr.core.domain.category.Category
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A category's name in each language (CLAUDE.md §8f): the server's Serbian in Cyrillic, that name made
 * Latin as every Latin text is, and the server's English.
 */
class CategoryNamesTest {
    @Test
    fun `a category reads in the language shown`() {
        assertEquals("Начин живота", categoryName(LIFESTYLE, Language.SERBIAN_CYRILLIC))
        assertEquals("Način života", categoryName(LIFESTYLE, Language.SERBIAN_LATIN))
        assertEquals("Lifestyle", categoryName(LIFESTYLE, Language.ENGLISH))
        // Capitals and the digraphs as SerbianScript writes them.
        assertEquals("Džungla", categoryName(JUNGLE, Language.SERBIAN_LATIN))
    }

    @Test
    fun `a category not read yet reads as its id in every language`() {
        Language.entries.forEach { language ->
            assertEquals("ANIMALS", categoryName("ANIMALS", known = listOf(LIFESTYLE), language = language))
            assertEquals(
                categoryName(LIFESTYLE, language),
                categoryName("LIFESTYLE", known = listOf(JUNGLE, LIFESTYLE), language = language),
            )
        }
    }

    private companion object {
        val LIFESTYLE = Category(id = "LIFESTYLE", nameSr = "Начин живота", nameEn = "Lifestyle")
        val JUNGLE = Category(id = "JUNGLE", nameSr = "Џунгла", nameEn = "Jungle")
    }
}

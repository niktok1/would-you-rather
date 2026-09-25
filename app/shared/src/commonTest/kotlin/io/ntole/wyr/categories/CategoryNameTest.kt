package io.ntole.wyr.categories

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.language.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/** A category named in each language the game is shown in (CLAUDE.md §8f). */
class CategoryNameTest {
    @Test
    fun `in Serbian Cyrillic a category is its Serbian name as the server sends it`() {
        assertEquals("Начин живота", LIFESTYLE.nameIn(Language.SERBIAN_CYRILLIC))
    }

    @Test
    fun `in Serbian Latin a category is its Serbian name transliterated`() {
        assertEquals("Način života", LIFESTYLE.nameIn(Language.SERBIAN_LATIN))
        assertEquals(
            "Džungla i ljubav",
            Category("X", "Џунгла и љубав", "Jungle and love").nameIn(Language.SERBIAN_LATIN),
        )
    }

    @Test
    fun `in English a category is its English name`() {
        assertEquals("Lifestyle", LIFESTYLE.nameIn(Language.ENGLISH))
    }

    @Test
    fun `a Serbian name a moderator wrote in Latin stays as written in both scripts`() {
        val rock = Category("ROCK", "Rok muzika", "Rock music")

        assertEquals("Rok muzika", rock.nameIn(Language.SERBIAN_CYRILLIC))
        assertEquals("Rok muzika", rock.nameIn(Language.SERBIAN_LATIN))
    }

    private companion object {
        val LIFESTYLE = Category(id = "LIFESTYLE", nameSr = "Начин живота", nameEn = "Lifestyle")
    }
}

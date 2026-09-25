package io.ntole.wyr.core.domain.category

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The server's `CategoryRules`, as the moderation app checks a category before it sends one. */
class CategoryRulesTest {
    @Test
    fun `an id is capital letters and digits and underscores exactly as typed`() {
        listOf("FOOD", "FAST_FOOD", "TOP10", "_", "A".repeat(CategoryRules.MAX_ID_LENGTH)).forEach { id ->
            assertTrue(CategoryRules.isId(id), id)
        }
        // Never trimmed or upper-cased: what is sent is the id forever.
        listOf("", "food", " FOOD", "FAST FOOD", "FAST-FOOD", "ХРАНА", "CAFÉ", "A".repeat(33)).forEach { id ->
            assertFalse(CategoryRules.isId(id), id)
        }
    }

    @Test
    fun `a name is one line of up to forty characters once trimmed`() {
        listOf("Храна", "Fast food", "  Food  ", "x".repeat(CategoryRules.MAX_NAME_LENGTH)).forEach { name ->
            assertTrue(CategoryRules.isName(name), name)
        }
        listOf("", "   ", "x".repeat(41), "Fast\nfood", "Fast\tfood", "Fast food").forEach { name ->
            assertFalse(CategoryRules.isName(name), name)
        }
    }
}

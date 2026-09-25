package io.ntole.wyr.admin.moderation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the Categories tab says about the list and about what is typed for a category. */
class CategoryLabelsTest {
    @Test
    fun `the list says whether it was read and how many it holds`() {
        assertEquals("Not read yet.", categoriesSummaryOf(null))
        assertEquals("No categories.", categoriesSummaryOf(emptyList()))
        assertEquals("1 category.", categoriesSummaryOf(listOf(FakeCategories.FOOD)))
        assertEquals("4 categories, oldest first.", categoriesSummaryOf(FakeCategories.LISTED))
    }

    @Test
    fun `a name says the rule it is held to until it breaks it`() {
        assertEquals("One line, at most 40 characters, trimmed.", nameHintOf(""))
        assertEquals("One line, at most 40 characters, trimmed.", nameHintOf(" Храна "))
        assertTrue(nameHintOf("Fast\nfood").startsWith("Not a name the server takes"))
        assertTrue(nameHintOf("   ").startsWith("Not a name the server takes"))
    }

    @Test
    fun `an id says the server makes one when none is typed and that one typed never changes`() {
        assertTrue(idHintOf("").startsWith("Blank: the server makes it from the English name"))
        assertEquals("Questions are filed under it, and it never changes.", idHintOf("FAST_FOOD"))
        assertEquals("Not an id the server takes: 1 to 32 of A-Z, 0-9 and _.", idHintOf("fast food"))
    }
}

package io.ntole.wyr.language

import kotlin.test.Test
import kotlin.test.assertEquals

/** How a translated text holds numbers and names (CLAUDE.md §8f). */
class TemplatesTest {
    @Test
    fun `a template takes its values where its placeholders are`() {
        assertEquals("3–20 знакова: a–z", "{0}–{1} знакова: {2}".fill(3, 20, "a–z"))
        assertEquals("20 then 3", "{1} then {0}".fill(3, 20))
    }

    @Test
    fun `a value is put in as it is`() {
        assertEquals("Rejected: see {1} and {0}", "Rejected: {0}".fill("see {1} and {0}", "never"))
    }

    @Test
    fun `a placeholder with no value is left as it is`() {
        assertEquals("Wait {0} s.", "Wait {0} s.".fill())
    }

    @Test
    fun `points read with their symbol in every language`() {
        assertEquals("123 P", pointsText(123))
        assertEquals("1 P", pointsText(1))
    }
}

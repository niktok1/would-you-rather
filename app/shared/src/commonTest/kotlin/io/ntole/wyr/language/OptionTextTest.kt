package io.ntole.wyr.language

import kotlin.test.Test
import kotlin.test.assertEquals

/** A question's option in each language (CLAUDE.md §8f): made Latin in Serbian Latin, as stored otherwise. */
class OptionTextTest {
    @Test
    fun `an option in Cyrillic is made Latin in Serbian Latin alone`() {
        val option = "Јести пљескавицу сваки дан"

        assertEquals(option, optionText(option, Language.SERBIAN_CYRILLIC))
        assertEquals("Jesti pljeskavicu svaki dan", optionText(option, Language.SERBIAN_LATIN))
        assertEquals(option, optionText(option, Language.ENGLISH))
    }

    @Test
    fun `an option written in Latin reads the same in every language`() {
        val option = "Fly but only a metre off the ground"

        Language.entries.forEach { language -> assertEquals(option, optionText(option, language), "$language") }
    }
}

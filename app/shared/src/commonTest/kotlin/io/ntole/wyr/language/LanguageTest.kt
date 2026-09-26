package io.ntole.wyr.language

import kotlin.test.Test
import kotlin.test.assertEquals

/** The languages, as the switch lists them and the device keeps them (CLAUDE.md §8f). */
class LanguageTest {
    @Test
    fun `the switch lists Serbian Cyrillic then Serbian Latin then English each in itself`() {
        assertEquals(listOf("Ћирилица", "Latinica", "English"), Language.entries.map { it.ownName })
    }

    /** Nothing reads the device's locale, so there is nothing else a first launch could open in. */
    @Test
    fun `a first launch opens in Serbian Cyrillic`() {
        assertEquals(Language.SERBIAN_CYRILLIC, Language.DEFAULT)
        assertEquals(Language.SERBIAN_CYRILLIC, Language.ofTag(null))
    }

    /** What the device keeps is the tag, so renaming an enum value keeps every player's language. */
    @Test
    fun `each language is kept as its own BCP 47 tag`() {
        assertEquals(listOf("sr-Cyrl", "sr-Latn", "en"), Language.entries.map { it.tag })
        Language.entries.forEach { language -> assertEquals(language, Language.ofTag(language.tag)) }
    }

    /** A newer build may keep a language this one does not have. */
    @Test
    fun `a tag this build does not know opens in Serbian Cyrillic`() {
        listOf("", "de", "sr", "SR-LATN", "SERBIAN_LATIN").forEach { tag ->
            assertEquals(Language.SERBIAN_CYRILLIC, Language.ofTag(tag), "\"$tag\"")
        }
    }

    /** English is hidden for the launch (CLAUDE.md §8b, *The launch*): its words stay, the menu offers Serbian. */
    @Test
    fun `the menu offers Serbian in both scripts and not English`() {
        assertEquals(listOf(Language.SERBIAN_CYRILLIC, Language.SERBIAN_LATIN), Language.OFFERED)
        assertEquals(Language.ENGLISH, Language.ofTag("en"), "a device that kept English keeps it")
    }
}

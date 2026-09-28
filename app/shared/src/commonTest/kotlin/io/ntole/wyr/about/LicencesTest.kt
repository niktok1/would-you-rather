package io.ntole.wyr.about

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The About screen's licences (CLAUDE.md §8d, *About*): each library under its own licence, and the
 * copyright notice an MIT or BSD licence asks to ship with the app beside it.
 */
class LicencesTest {
    @Test
    fun `each library is under its own licence and MIT and BSD ones carry their notice`() {
        val byName = OPEN_SOURCE_LIBRARIES.associateBy { it.name }

        val slf4j = assertNotNull(byName["SLF4J API"], "SLF4J, which Ktor brings")
        assertEquals("MIT License", slf4j.licence)
        assertEquals("Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)", slf4j.notice)
        val skia = assertNotNull(byName["Skia (in Skiko)"], "Skia, which Skiko builds in")
        assertEquals("BSD 3-Clause License", skia.licence)
        assertEquals("Copyright (c) 2011 Google Inc.", skia.notice)
        assertEquals("Apache License 2.0", assertNotNull(byName["Stately"], "Stately, which Ktor brings").licence)
        listOf("Firebase Android SDK", "Error Prone annotations", "javax.inject").forEach { name ->
            assertEquals("Apache License 2.0", assertNotNull(byName[name], "$name, which pushes bring").licence)
        }

        OPEN_SOURCE_LIBRARIES.filter { it.licence.startsWith("MIT") || it.licence.startsWith("BSD") }.forEach {
            assertNotNull(it.notice, "${it.name}: its licence asks for its notice")
        }
        assertEquals(OPEN_SOURCE_LIBRARIES.size, byName.size, "a library named twice")
    }
}

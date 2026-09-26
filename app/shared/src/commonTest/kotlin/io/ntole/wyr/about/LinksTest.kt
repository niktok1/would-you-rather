package io.ntole.wyr.about

import kotlin.test.Test
import kotlin.test.assertEquals

/** Opening a link where nothing on the device may open it (CLAUDE.md §8d, *About*). */
class LinksTest {
    @Test
    fun `the first link something opens is the one opened and the rest are not tried`() {
        val tried = mutableListOf<String>()
        openFirst(listOf("market://a", "https://a", "https://b")) { uri ->
            tried += uri
            if (uri.startsWith("market:")) throw IllegalStateException("no store")
        }

        assertEquals(listOf("market://a", "https://a"), tried)
    }

    @Test
    fun `nothing happens when nothing opens any of them`() {
        val tried = mutableListOf<String>()
        openFirst(listOf("market://a", "https://a")) { uri ->
            tried += uri
            throw IllegalArgumentException("Can't open $uri.")
        }

        assertEquals(listOf("market://a", "https://a"), tried)
    }
}

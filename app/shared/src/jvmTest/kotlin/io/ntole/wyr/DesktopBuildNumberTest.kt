package io.ntole.wyr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The build number the desktop client hands `initKoin` (CLAUDE.md §8g, *The build number*). */
class DesktopBuildNumberTest {
    @Test
    fun `the build number is the wyr_app_build property's`() {
        assertEquals(10203, desktopBuildNumber(mapOf("wyr.app.build" to "10203")))
    }

    @Test
    fun `no property or none that is a whole number is no build number`() {
        assertNull(desktopBuildNumber(emptyMap()))
        assertNull(desktopBuildNumber(mapOf("wyr.app.build" to "1.0.0")))
    }
}

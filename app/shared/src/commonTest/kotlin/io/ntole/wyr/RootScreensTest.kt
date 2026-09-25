package io.ntole.wyr

import kotlin.test.Test
import kotlin.test.assertEquals

/** The root screens every build shows, whatever server it talks to, and which it opens on. */
class RootScreensTest {
    @Test
    fun `the app opens on Play with Submit and Account beside it and nothing else`() {
        assertEquals(listOf(RootScreen.Play, RootScreen.Submit, RootScreen.Account), RootScreen.entries)
    }
}

package io.ntole.wyr

import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Which root screens each environment's build shows, and which it opens on. */
class RootScreensTest {
    @Test
    fun `a prod build shows the Play screen alone`() {
        assertEquals(listOf(RootScreen.Play), rootScreensFor(WyrEnvironment.PROD))
    }

    @Test
    fun `no build without developer tools can reach the console`() {
        WyrEnvironment.entries.filterNot { it.showsDeveloperTools }.forEach { environment ->
            assertFalse(RootScreen.Console in rootScreensFor(environment), environment.name)
        }
    }

    @Test
    fun `local and dev builds open on the console with Play beside it`() {
        listOf(WyrEnvironment.LOCAL, WyrEnvironment.DEV).forEach { environment ->
            assertEquals(listOf(RootScreen.Console, RootScreen.Play), rootScreensFor(environment), environment.name)
        }
    }
}

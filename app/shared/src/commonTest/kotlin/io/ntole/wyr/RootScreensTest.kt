package io.ntole.wyr

import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which root screens each environment's build shows, and which it opens on. */
class RootScreensTest {
    @Test
    fun `a prod build opens on Play with Submit and Account beside it`() {
        assertEquals(
            listOf(RootScreen.Play, RootScreen.Submit, RootScreen.Account),
            rootScreensFor(WyrEnvironment.PROD),
        )
    }

    @Test
    fun `no build without developer tools can reach the console`() {
        WyrEnvironment.entries.filterNot { it.showsDeveloperTools }.forEach { environment ->
            assertFalse(RootScreen.Console in rootScreensFor(environment), environment.name)
        }
    }

    @Test
    fun `every build can reach the Account screen`() {
        WyrEnvironment.entries.forEach { environment ->
            assertTrue(RootScreen.Account in rootScreensFor(environment), environment.name)
        }
    }

    @Test
    fun `every build can reach the Submit screen`() {
        WyrEnvironment.entries.forEach { environment ->
            assertTrue(RootScreen.Submit in rootScreensFor(environment), environment.name)
        }
    }

    @Test
    fun `local and dev builds open on the console with the game beside it`() {
        listOf(WyrEnvironment.LOCAL, WyrEnvironment.DEV).forEach { environment ->
            assertEquals(
                listOf(RootScreen.Console, RootScreen.Play, RootScreen.Submit, RootScreen.Account),
                rootScreensFor(environment),
                environment.name,
            )
        }
    }
}

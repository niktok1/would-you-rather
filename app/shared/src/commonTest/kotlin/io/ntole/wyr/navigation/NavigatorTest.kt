package io.ntole.wyr.navigation

import androidx.compose.runtime.saveable.SaverScope
import io.ntole.wyr.navigation.Screen.Account
import io.ntole.wyr.navigation.Screen.Auth
import io.ntole.wyr.navigation.Screen.Categories
import io.ntole.wyr.navigation.Screen.Home
import io.ntole.wyr.navigation.Screen.Play
import io.ntole.wyr.navigation.Screen.Submit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The game's back stack (CLAUDE.md §8d, *Navigation*), driven as the screens' buttons drive it. */
class NavigatorTest {
    @Test
    fun `the app opens on Home with nothing to go back to`() {
        val navigator = Navigator()

        assertEquals(Home, navigator.current)
        assertEquals(listOf(Home), navigator.screens)
        assertFalse(navigator.canGoBack)
    }

    /** Android's back then does what it does without the app's own: it leaves the app. */
    @Test
    fun `back from Home does nothing and says so`() {
        val navigator = Navigator()

        assertFalse(navigator.back())
        assertEquals(listOf(Home), navigator.screens)
    }

    @Test
    fun `Play opens over Home and back returns to Home`() {
        val navigator = Navigator()

        navigator.open(Play)
        assertEquals(listOf(Home, Play), navigator.screens)
        assertTrue(navigator.canGoBack)

        assertTrue(navigator.back())
        assertEquals(listOf(Home), navigator.screens)
    }

    @Test
    fun `Account opens over Play and back returns to Play`() {
        val navigator = navigatorAt(Play)

        navigator.open(Account)
        assertEquals(listOf(Home, Play, Account), navigator.screens)

        navigator.back()
        assertEquals(Play, navigator.current)
    }

    @Test
    fun `Account opens over Home and back returns to Home`() {
        val navigator = Navigator()

        navigator.open(Account)
        navigator.back()

        assertEquals(listOf(Home), navigator.screens)
    }

    @Test
    fun `the home icon on Play goes back to Home`() {
        val navigator = navigatorAt(Play)

        navigator.open(Home)

        assertEquals(listOf(Home), navigator.screens)
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun `Submit opens from Account and back returns to Account then to Play`() {
        val navigator = navigatorAt(Play, Account)

        navigator.open(Submit)
        assertEquals(listOf(Home, Play, Account, Submit), navigator.screens)

        navigator.back()
        assertEquals(Account, navigator.current)
        navigator.back()
        assertEquals(Play, navigator.current)
        navigator.back()
        assertEquals(Home, navigator.current)
        assertFalse(navigator.back())
    }

    @Test
    fun `the Auth page opens from Account and back returns to Account`() {
        val navigator = navigatorAt(Play, Account)

        navigator.open(Auth)
        assertEquals(listOf(Home, Play, Account, Auth), navigator.screens)

        navigator.back()
        assertEquals(Account, navigator.current)
    }

    @Test
    fun `the categories open over Play and back returns to Play`() {
        val navigator = navigatorAt(Play)

        navigator.open(Categories)
        assertEquals(listOf(Home, Play, Categories), navigator.screens)

        assertTrue(navigator.back())
        assertEquals(listOf(Home, Play), navigator.screens)
    }

    /** No screen is on the stack twice, so no back ever shows the one it leaves. */
    @Test
    fun `a screen opened while it is on the stack is gone back to`() {
        val navigator = navigatorAt(Play, Account, Submit)

        navigator.open(Play)

        assertEquals(listOf(Home, Play), navigator.screens)
    }

    @Test
    fun `opening the screen shown changes nothing`() {
        val navigator = navigatorAt(Play)

        navigator.open(Play)

        assertEquals(listOf(Home, Play), navigator.screens)
    }

    /** An Android activity made anew, on a rotation say, shows the screen it showed. */
    @Test
    fun `the back stack comes back whole from saved state`() {
        listOf(
            listOf(Home),
            listOf(Home, Play),
            listOf(Home, Account, Submit),
            listOf(Home, Play, Account, Submit),
            listOf(Home, Play, Account, Auth),
            listOf(Home, Play, Categories),
            listOf(Home, Account, Screen.About),
        ).forEach { screens ->
            val saved = save(Navigator(screens))

            assertEquals(screens, restore(saved).screens)
        }
    }

    @Test
    fun `saved state this build cannot read whole starts again at Home`() {
        listOf(
            listOf("home", "play", "leaderboard"),
            listOf("play"),
            emptyList(),
            listOf("home", "play", "play"),
        ).forEach { saved ->
            assertEquals(listOf(Home), restore(saved).screens, "$saved")
        }
    }

    /** A stack as the screens' buttons build it, from Home through [screens]. */
    private fun navigatorAt(vararg screens: Screen): Navigator = Navigator().apply { screens.forEach(::open) }

    private fun save(navigator: Navigator): Any {
        val saved = with(Navigator.Saver) { SaverScope { true }.save(navigator) }
        return checkNotNull(saved)
    }

    private fun restore(saved: Any): Navigator = checkNotNull(Navigator.Saver.restore(saved))
}

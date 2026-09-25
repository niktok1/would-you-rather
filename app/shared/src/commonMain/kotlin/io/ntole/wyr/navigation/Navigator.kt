package io.ntole.wyr.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/**
 * The game's back stack, made by hand (CLAUDE.md §8d, *Navigation*): the screens opened, [Screen.Home]
 * at the bottom and the one shown, [current], on top. Snapshot state, so a composition reading it
 * shows each change.
 *
 * No screen is on the stack twice: [open] goes back to one already there instead, so the stack is
 * at most every screen once, and the home icon is simply `open(Screen.Home)`.
 */
class Navigator internal constructor(
    screens: List<Screen>,
) {
    constructor() : this(listOf(Screen.Home))

    init {
        require(screens.firstOrNull() == Screen.Home) { "a back stack starts at Home: $screens" }
        require(screens.distinct() == screens) { "a screen is on the back stack twice: $screens" }
    }

    /** The back stack, Home first and [current] last. */
    var screens: List<Screen> by mutableStateOf(screens)
        private set

    /** The screen shown. */
    val current: Screen get() = screens.last()

    /** Whether there is a screen to go back to: everywhere but Home. */
    val canGoBack: Boolean get() = screens.size > 1

    /** Shows [screen] over the one shown, or goes back to it, dropping all above, if it is on the stack. */
    fun open(screen: Screen) {
        val at = screens.indexOf(screen)
        screens = if (at < 0) screens + screen else screens.subList(0, at + 1).toList()
    }

    /**
     * Goes back to the screen before, and says whether there was one. At Home there is none, so it
     * does nothing and answers false: Android's back then leaves the app.
     */
    fun back(): Boolean {
        if (!canGoBack) return false
        screens = screens.dropLast(1)
        return true
    }

    companion object {
        /**
         * Keeps the back stack in saved state, as each screen's key, so an Android activity made anew,
         * on a rotation say, shows the screen it showed, whose ViewModel it kept. A saved stack this
         * build cannot read whole starts again at Home.
         */
        val Saver: Saver<Navigator, Any> =
            listSaver(
                save = { navigator -> navigator.screens.map(Screen::key) },
                restore = { keys ->
                    val screens = keys.map(Screen::ofKey)
                    val readable = screens.filterNotNull()
                    val whole =
                        readable.size == screens.size && readable.firstOrNull() == Screen.Home &&
                            readable.distinct() == readable
                    if (whole) Navigator(readable) else Navigator()
                },
            )
    }
}

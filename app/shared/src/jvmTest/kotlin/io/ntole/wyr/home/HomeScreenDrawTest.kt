package io.ntole.wyr.home

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.descriptions
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.nodes
import io.ntole.wyr.play.COUNT_UP_MILLIS
import io.ntole.wyr.renderAt
import io.ntole.wyr.settle
import io.ntole.wyr.sizeNeeded
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Home screen (CLAUDE.md §8d, *Navigation*, *Home picks*) drawn off screen at two phones' sizes and
 * a desktop window's, in each theme and each language, and read through its semantics: the game's name,
 * two Play buttons in the cards' colours, each with its share once read, and the account icon.
 */
class HomeScreenDrawTest {
    @Test
    fun `the screen draws in both themes and every language`() {
        listOf(false, true).forEach { dark ->
            Language.entries.forEach { language ->
                listOf(null, PICKS).forEach { picks ->
                    SIZES.forEach { (width, height) ->
                        val scene = scene(language, picks, dark, width, height)
                        try {
                            assertEquals(width, scene.render().width)
                        } finally {
                            scene.close()
                        }
                    }
                }
            }
        }
    }

    /**
     * An iPhone SE (667 high) less its status bar (20) and 48 more, as the other screens are held,
     * measured at 375 wide, the name wrapping as it will on the narrowest phone, the shares shown.
     */
    @Test
    fun `the screen fits a short phone in every language`() {
        Language.entries.forEach { language ->
            val (_, height) =
                sizeNeeded(SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT) {
                    WyrTheme { WyrStrings(language) { HomeScreen(picks = PICKS, onPlay = {}, onAccount = {}) } }
                }
            assertTrue(height <= SHORT_PHONE_HEIGHT, "$language needs $height of $SHORT_PHONE_HEIGHT")
        }
    }

    /** The user asked for less text: the name, Play twice, and each one's share once read. */
    @Test
    fun `the screen shows the name and two Play buttons with their shares and the account icon`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language)
            listOf(
                null to listOf(strings.gameName, strings.play, strings.play),
                // Nobody has tapped yet: no share to show.
                Tally(votesA = 0, votesB = 0) to listOf(strings.gameName, strings.play, strings.play),
                PICKS to
                    listOf(
                        strings.gameName,
                        strings.play,
                        strings.playScreen.percent(75),
                        strings.play,
                        strings.playScreen.percent(25),
                    ),
            ).forEach { (picks, expected) ->
                val scene = scene(language, picks)
                try {
                    assertEquals(expected, scene.texts(), "$language with $picks")
                    assertEquals(listOf(strings.account), scene.descriptions(), "$language")
                } finally {
                    scene.close()
                }
            }
        }
    }

    /**
     * Stacked on a phone, card A's colour above card B's and under the name; side by side on a desktop
     * window, card A first, as the Play screen stands its cards.
     */
    @Test
    fun `the buttons stand as the Play screen's cards do`() {
        val strings = stringsOf(Language.DEFAULT)
        listOf(
            SHORT_PHONE_WIDTH to SHORT_PHONE_HEIGHT,
            WINDOW_WIDTH to WINDOW_HEIGHT,
        ).forEach { (width, height) ->
            val scene = scene(Language.DEFAULT, PICKS, width = width, height = height)
            try {
                val name = scene.nodes().single { strings.gameName in it.texts }.boundsInRoot
                val (a, b) = scene.nodes().filter { strings.play in it.texts }.map { it.boundsInRoot }
                assertTrue(name.bottom <= a.top && name.bottom <= b.top, "the name is not above the buttons")
                if (width == WINDOW_WIDTH) {
                    assertTrue(a.top == b.top && a.right < b.left && abs(a.width - b.width) <= 1f, "$a beside $b")
                } else {
                    assertTrue(a.bottom < b.top && a.width == b.width, "$a above $b")
                }
            } finally {
                scene.close()
            }
        }
    }

    /** Each share counts up from 0 as the reveal's does, drawn: a screen reader reads its final value from the start. */
    @Test
    fun `a screen reader reads each share as it is throughout the count up`() {
        val strings = stringsOf(Language.DEFAULT).playScreen
        val scene = scene(Language.DEFAULT, PICKS)
        try {
            listOf(0L, COUNTED_UP / 2, COUNTED_UP).forEach { time ->
                scene.renderAt(time)
                val shares = scene.texts().filter { it.endsWith("%") }
                assertEquals(listOf(strings.percent(75), strings.percent(25)), shares, "at $time")
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `each Play button starts the game with its side and the account icon opens Account`() {
        Language.entries.forEach { language ->
            val tapped = mutableListOf<String>()
            val scene =
                scene(language, PICKS, onPlay = { side -> tapped += "play $side" }, onAccount = { tapped += "account" })
            try {
                val strings = stringsOf(language)
                scene.nodes().filter { strings.play in it.texts }.forEach { button ->
                    assertNotNull(button.config.getOrNull(SemanticsActions.OnClick)?.action)()
                    scene.settle()
                }
                scene.tap(strings.account)
                assertEquals(listOf("play ${Side.A}", "play ${Side.B}", "account"), tapped, "$language")
            } finally {
                scene.close()
            }
        }
    }

    private fun scene(
        language: Language,
        picks: Tally?,
        dark: Boolean = false,
        width: Int = SHORT_PHONE_WIDTH,
        height: Int = SHORT_PHONE_HEIGHT,
        onPlay: (Side) -> Unit = {},
        onAccount: () -> Unit = {},
    ): ImageComposeScene =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            WyrTheme(darkTheme = dark) {
                WyrStrings(language) { HomeScreen(picks = picks, onPlay = onPlay, onAccount = onAccount) }
            }
        }.also { it.render() }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        /** A desktop window as Compose first opens one, less its title bar: the buttons side by side. */
        const val WINDOW_WIDTH = 800
        const val WINDOW_HEIGHT = 600

        val SIZES = listOf(WIDTH to HEIGHT, SHORT_PHONE_WIDTH to SHORT_PHONE_HEIGHT, WINDOW_WIDTH to WINDOW_HEIGHT)

        /** Three taps on card A's colour to every one on card B's. */
        val PICKS = Tally(votesA = 30, votesB = 10)

        /** The scene's clock, in nanoseconds, once a share has counted up. */
        const val COUNTED_UP = COUNT_UP_MILLIS * 1_000_000L
    }
}

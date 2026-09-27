package io.ntole.wyr.home

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.descriptions
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.Strings
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.nodes
import io.ntole.wyr.passTime
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
 * two small Play buttons in the cards' colours, and the account icon; and a tap on one, whose reveal
 * counts both shares up and then hands over to the game.
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
                    WyrTheme {
                        WyrStrings(
                            language,
                        ) { HomeScreen(picks = PICKS, onPick = {}, onPlay = {}, onAccount = {}) }
                    }
                }
            assertTrue(height <= SHORT_PHONE_HEIGHT, "$language needs $height of $SHORT_PHONE_HEIGHT")
        }
    }

    /** The user asked for less text: the name and Play twice, and no share until a button is tapped. */
    @Test
    fun `the screen shows the name and two Play buttons and the account icon`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language)
            listOf(null, Tally(votesA = 0, votesB = 0), PICKS).forEach { picks ->
                val scene = scene(language, picks)
                try {
                    assertEquals(listOf(strings.gameName, strings.play, strings.play), scene.texts(), "$language")
                    assertEquals(listOf(strings.account), scene.descriptions(), "$language")
                } finally {
                    scene.close()
                }
            }
        }
    }

    /**
     * Small and side by side under the name, card A's colour first, on a phone and on a desktop window
     * alike: nowhere near the screen's height, and no wider than the pair may stand.
     */
    @Test
    fun `the buttons are small and side by side under the name`() {
        val strings = stringsOf(Language.DEFAULT)
        SIZES.forEach { (width, height) ->
            val scene = scene(Language.DEFAULT, PICKS, width = width, height = height)
            try {
                val name = scene.nodes().single { strings.gameName in it.texts }.boundsInRoot
                val (a, b) = scene.nodes().filter { strings.play in it.texts }.map { it.boundsInRoot }
                assertTrue(name.bottom <= a.top, "the name is not above the buttons at $width")
                assertTrue(a.top == b.top && a.right < b.left && abs(a.width - b.width) <= 1f, "$a beside $b")
                assertTrue(a.height < height / 3f, "$a fills the screen at $width x $height")
                assertTrue(b.right - a.left <= BUTTONS_MAX_WIDTH, "${b.right - a.left} wide at $width")
            } finally {
                scene.close()
            }
        }
    }

    /**
     * A tap reveals both shares, the tap counted with every player's, outlines the button tapped and holds
     * the other still; nothing moves, and a screen reader reads each share as it ends throughout the count.
     */
    @Test
    fun `a tap reveals both shares with the tap counted and moves nothing`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language)
            val picked = mutableListOf<Side>()
            val scene = scene(language, PICKS, onPick = { picked += it })
            try {
                val before = scene.nodes().filter { strings.play in it.texts }.map { it.boundsInRoot }
                scene.tapPlay(strings, Side.A)
                assertEquals(listOf(Side.A), picked)

                // 31 of 41 taps on card A's colour, this one among them.
                val shares = listOf(strings.playScreen.percent(76), strings.playScreen.percent(24))
                listOf(0L, HOME_COUNT_UP_MILLIS / 2L, HOME_COUNT_UP_MILLIS.toLong()).forEach { step ->
                    scene.passTime(step)
                    assertEquals(shares, scene.texts().filter { it.endsWith("%") }, "$language after $step")
                }
                val buttons = scene.nodes().filter { strings.play in it.texts }
                assertEquals(before, buttons.map { it.boundsInRoot }, "$language: the reveal moved a button")
                assertNotNull(buttons[1].config.getOrNull(SemanticsProperties.Disabled), "B still takes a tap")
                scene.tapPlay(strings, Side.B)
                assertEquals(listOf(Side.A), picked, "$language: a second tap")
            } finally {
                scene.close()
            }
        }
    }

    /** The count up, a moment's hold and Home's fade, then the game: once, and not a frame before. */
    @Test
    fun `the game starts once the reveal is over`() {
        var played = 0
        val scene = scene(Language.DEFAULT, PICKS, onPlay = { played++ })
        try {
            scene.tapPlay(stringsOf(Language.DEFAULT), Side.B)
            scene.passTime(HOME_REVEAL_MILLIS - FRAME)
            assertEquals(0, played, "before the reveal is over")
            scene.passTime(FRAME * 2)
            assertEquals(1, played)
            scene.passTime(HOME_REVEAL_MILLIS.toLong())
            assertEquals(1, played, "once")
        } finally {
            scene.close()
        }
    }

    /** No picks read, offline or not yet: nothing to reveal, so the game starts at once. */
    @Test
    fun `with no picks read a tap starts the game at once`() {
        val events = mutableListOf<String>()
        val scene =
            scene(Language.DEFAULT, picks = null, onPick = { events += "pick $it" }, onPlay = { events += "play" })
        try {
            scene.tapPlay(stringsOf(Language.DEFAULT), Side.A)
            assertEquals(listOf("pick ${Side.A}", "play"), events)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `each Play button is its side and the account icon opens Account`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language)
            Side.entries.forEach { side ->
                val picked = mutableListOf<Side>()
                val scene = scene(language, PICKS, onPick = { picked += it })
                try {
                    scene.tapPlay(strings, side)
                    assertEquals(listOf(side), picked, "$language")
                } finally {
                    scene.close()
                }
            }
            var opened = 0
            val scene = scene(language, PICKS, onAccount = { opened++ })
            try {
                scene.tap(strings.account)
            } finally {
                scene.close()
            }
            assertEquals(1, opened, "$language")
        }
    }

    /**
     * A decision the player has not seen (CLAUDE.md §8d, *The notice of a decision*): the account icon
     * has its dot, named for a screen reader, and nothing else on Home changes, still inside a short
     * phone's 599 in every language.
     */
    @Test
    fun `with news the account icon is named for it and nothing else changes`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language)
            val dotted = strings.notice.accountWithNews.fill(strings.account)
            var opened = 0
            val scene = scene(language, PICKS, onAccount = { opened++ }, news = true)
            try {
                assertEquals(listOf(dotted), scene.descriptions(), "$language")
                assertEquals(3, scene.texts().size, "$language: the name and two buttons")
                scene.tap(dotted)
            } finally {
                scene.close()
            }
            assertEquals(1, opened, "$language")
            val (_, height) =
                sizeNeeded(SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT) {
                    WyrTheme {
                        WyrStrings(
                            language,
                        ) { HomeScreen(picks = PICKS, onPick = {}, onPlay = {}, onAccount = {}, news = true) }
                    }
                }
            assertTrue(height <= SHORT_PHONE_HEIGHT, "$language needs $height of $SHORT_PHONE_HEIGHT")
        }
    }

    /** Taps [side]'s Play button, the first from the left being card A's colour. */
    private fun ImageComposeScene.tapPlay(
        strings: Strings,
        side: Side,
    ) {
        val button = nodes().filter { strings.play in it.texts }[side.ordinal]
        button.config
            .getOrNull(SemanticsActions.OnClick)
            ?.action
            ?.invoke()
        settle()
    }

    private fun scene(
        language: Language,
        picks: Tally?,
        dark: Boolean = false,
        width: Int = SHORT_PHONE_WIDTH,
        height: Int = SHORT_PHONE_HEIGHT,
        onPick: (Side) -> Unit = {},
        onPlay: () -> Unit = {},
        onAccount: () -> Unit = {},
        news: Boolean = false,
    ): ImageComposeScene =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            WyrTheme(darkTheme = dark) {
                WyrStrings(language) {
                    HomeScreen(picks = picks, onPick = onPick, onPlay = onPlay, onAccount = onAccount, news = news)
                }
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

        /** The widest the two buttons stand together, the theme's `homeButtonsMaxWidth` at one pixel a dp. */
        const val BUTTONS_MAX_WIDTH = 328f

        /** A frame of the scene's clock, in milliseconds, as [passTime] steps it. */
        const val FRAME = 100L
    }
}

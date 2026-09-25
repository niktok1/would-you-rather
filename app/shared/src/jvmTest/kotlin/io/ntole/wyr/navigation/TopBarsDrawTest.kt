package io.ntole.wyr.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.ntole.wyr.descriptions
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.Strings
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.play.CategoriesPlayed
import io.ntole.wyr.sizeNeeded
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrDefaultDimens
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The top bars above the Play, Account, Auth, Submit and Categories screens (CLAUDE.md §8d,
 * *Navigation*), drawn off screen at a short phone's width, in each theme and each language, and read
 * through their semantics. Home's is drawn with the Home screen (`HomeScreenDrawTest`).
 */
class TopBarsDrawTest {
    @Test
    fun `every top bar draws in both themes and every language`() {
        BARS.forEach { bar ->
            listOf(false, true).forEach { dark ->
                Language.entries.forEach { language ->
                    val scene = scene(bar, language, dark)
                    try {
                        assertEquals(SHORT_PHONE_WIDTH, scene.render().width)
                    } finally {
                        scene.close()
                    }
                }
            }
        }
    }

    /**
     * Each bar takes the tab row's height, 48, and no more, so the screen under it keeps the 599 of
     * an iPhone SE's 667 its own draw test holds it to (667 less the status bar's 20 and the bar's
     * 48); and at 375 wide it needs no more width than it has, so nothing in it is cut short.
     */
    @Test
    fun `every top bar fits a short phone in every language`() {
        BARS.forEach { bar ->
            Language.entries.forEach { language ->
                val (width, height) =
                    sizeNeeded(SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT) {
                        WyrTheme { WyrStrings(language) { bar.draw(Actions()) } }
                    }
                assertEquals(TOP_BAR_HEIGHT, height, "${bar.name} in $language")
                if (!bar.cutShort) {
                    assertTrue(
                        width <= SHORT_PHONE_WIDTH,
                        "${bar.name} in $language needs $width of $SHORT_PHONE_WIDTH",
                    )
                }
            }
        }
        assertEquals(SHORT_PHONE_HEIGHT, IPHONE_SE_HEIGHT - STATUS_BAR - TOP_BAR_HEIGHT)
    }

    @Test
    fun `every top bar shows what it holds named in the language shown`() {
        BARS.forEach { bar ->
            Language.entries.forEach { language ->
                val scene = scene(bar, language)
                try {
                    val strings = stringsOf(language)
                    assertEquals(bar.icons(strings), scene.descriptions(), "${bar.name} in $language")
                    assertEquals(bar.texts(strings), scene.texts(), "${bar.name} in $language")
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun `every button on a top bar does what it says`() {
        BARS.forEach { bar ->
            Language.entries.forEach { language ->
                val actions = Actions()
                val scene = scene(bar, language, actions = actions)
                try {
                    val strings = stringsOf(language)
                    (bar.icons(strings) + bar.texts(strings)).forEach(scene::tap)
                    assertEquals(bar.taps, actions.tapped, "${bar.name} in $language")
                } finally {
                    scene.close()
                }
            }
        }
    }

    /** What the bars' buttons were tapped for, in order. */
    private class Actions {
        val tapped = mutableListOf<String>()

        fun record(what: String): () -> Unit = { tapped += what }
    }

    /**
     * A top bar: how to draw it, the icons it names and the texts it shows, left to right, and what
     * tapping each of them in that order asks for.
     */
    private class Bar(
        val name: String,
        val icons: (Strings) -> List<String>,
        val texts: (Strings) -> List<String>,
        val taps: List<String>,
        /** Whether a text in it runs past its room on purpose, cut short on its one line. */
        val cutShort: Boolean = false,
        val draw: @Composable (Actions) -> Unit,
    )

    private fun scene(
        bar: Bar,
        language: Language,
        dark: Boolean = false,
        actions: Actions = Actions(),
    ): ImageComposeScene =
        ImageComposeScene(width = SHORT_PHONE_WIDTH, height = TOP_BAR_HEIGHT, density = Density(1f)) {
            WyrTheme(darkTheme = dark) { WyrStrings(language) { bar.draw(actions) } }
        }.also { it.render() }

    private companion object {
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599
        const val IPHONE_SE_HEIGHT = 667
        const val STATUS_BAR = 20

        val TOP_BAR_HEIGHT = WyrDefaultDimens.topBarHeight.value.toInt()

        /** The server's first five categories, every one played, as the Play screen names them in Cyrillic. */
        const val LONG_SELECTION = "Храна, Начин живота, Етика, Супермоћи, Апсурдно"

        val BARS =
            listOf(
                Bar(
                    name = "Play's",
                    icons = { listOf(it.home, it.account) },
                    texts = { listOf(it.allCategories) },
                    taps = listOf("home", "account", "categories"),
                    draw = { actions ->
                        PlayTopBar(onHome = actions.record("home"), onAccount = actions.record("account")) {
                            CategoriesPlayed(
                                text = LocalStrings.current.allCategories,
                                enabled = true,
                                onClick = actions.record("categories"),
                            )
                        }
                    },
                ),
                // Every category played, as long a line as five names make: cut short in the middle,
                // never the icons, and never a second line.
                Bar(
                    name = "Play's, with a long selection",
                    icons = { listOf(it.home, it.account) },
                    texts = { listOf(LONG_SELECTION) },
                    taps = listOf("home", "account", "categories"),
                    cutShort = true,
                    draw = { actions ->
                        PlayTopBar(onHome = actions.record("home"), onAccount = actions.record("account")) {
                            CategoriesPlayed(
                                text = LONG_SELECTION,
                                enabled = true,
                                onClick = actions.record("categories"),
                            )
                        }
                    },
                ),
                Bar(
                    name = "Account's, the Auth page's, Submit's and the Categories screen's",
                    icons = { listOf(it.back) },
                    texts = { emptyList() },
                    taps = listOf("back"),
                    draw = { BackTopBar(onBack = it.record("back")) },
                ),
            )
    }
}

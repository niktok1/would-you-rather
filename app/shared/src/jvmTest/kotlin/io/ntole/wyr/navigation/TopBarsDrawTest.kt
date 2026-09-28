package io.ntole.wyr.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import io.ntole.wyr.descriptions
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.Strings
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.nodes
import io.ntole.wyr.play.CategoriesPlayed
import io.ntole.wyr.sizeNeeded
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrDarkColors
import io.ntole.wyr.theme.WyrDefaultDimens
import io.ntole.wyr.theme.WyrLightColors
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The top bars above the Play, Account, Auth, Submit, Categories and About screens (CLAUDE.md §8d,
 * *Navigation*), drawn off screen at a short phone's width, in each theme and each language, and read
 * through their semantics. Home's is drawn with the Home screen (`HomeScreenDrawTest`), and here with
 * the dot of a decision not seen yet, as Play's is.
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

    /**
     * The notice's dot is drawn inside the account icon's touch target, 48 square, on Home's bar and
     * Play's, in both themes, and nowhere else on the bar: a bar with no news draws none.
     */
    @Test
    fun `the dot is drawn inside the account icon's touch target and only with news`() {
        listOf(WyrLightColors, WyrDarkColors).forEach { colors ->
            BARS.forEach { bar ->
                val strings = stringsOf(Language.DEFAULT)
                val dotted = strings.notice.accountWithNews.fill(strings.account)
                val scene = scene(bar, Language.DEFAULT, dark = colors.isDark)
                try {
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    val dot =
                        (0 until pixels.height).flatMap { y ->
                            (0 until pixels.width)
                                .filter { x -> pixels[x, y].toArgb() == colors.optionA.toArgb() }
                                .map { x -> Offset(x.toFloat(), y.toFloat()) }
                        }
                    val name = "${bar.name}, dark: ${colors.isDark}"
                    if (dotted !in bar.icons(strings)) {
                        assertEquals(emptyList(), dot, "$name: no dot")
                    } else {
                        assertTrue(dot.isNotEmpty(), "$name: no dot drawn")
                        val icon = scene.nodes().single { dotted in it.descriptions }.boundsInRoot
                        // The icon button's bounds are 40 square inside its touch target of 48.
                        val target = icon.inflate((TOUCH_TARGET - icon.width) / 2)
                        assertEquals(TOUCH_TARGET.toFloat(), target.width, name)
                        assertTrue(dot.all { target.contains(it) }, "$name: a dot outside $target")
                    }
                } finally {
                    scene.close()
                }
            }
        }
    }

    /**
     * Play's bar is one icon on each side, home and the account icon, so the categories played stand
     * in its exact middle, in both themes and every language, with the dot or without, whether they fit
     * or are cut short in the width the two icons leave them (CLAUDE.md §8d, *The Play screen*).
     */
    @Test
    fun `the categories played stand in the exact middle of Play's bar`() {
        val bars = BARS.filter { it.name.startsWith("Play's") }
        assertEquals(4, bars.size)
        bars.forEach { bar ->
            listOf(false, true).forEach { dark ->
                Language.entries.forEach { language ->
                    val scene = scene(bar, language, dark)
                    try {
                        val text = bar.texts(stringsOf(language)).single()
                        val categories = scene.nodes().single { text in it.texts }.boundsInRoot
                        val name = "${bar.name} in $language, dark: $dark"
                        assertEquals(SHORT_PHONE_WIDTH / 2f, categories.center.x, HALF_PIXEL, name)
                        assertTrue(categories.left >= 0f && categories.right <= SHORT_PHONE_WIDTH, name)
                    } finally {
                        scene.close()
                    }
                }
            }
        }
    }

    /**
     * The dot takes no room from the categories played: on Play's bar a long selection has the same
     * room with news as without, in every language, so it is cut short where it is without the dot.
     */
    @Test
    fun `the dot leaves a long selection its room`() {
        val (plain, withNews) =
            listOf("Play's, with a long selection", "Play's, with news and a long selection").map { name ->
                BARS.single { it.name == name }
            }
        Language.entries.forEach { language ->
            val (without, with) =
                listOf(plain, withNews).map { bar ->
                    val scene = scene(bar, language)
                    try {
                        scene.nodes().single { LONG_SELECTION in it.texts }.boundsInRoot
                    } finally {
                        scene.close()
                    }
                }
            assertEquals(without, with, "$language")
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

        /** How far off the bar's middle the categories may stand: the rounding of a layout to pixels. */
        const val HALF_PIXEL = 0.5f

        /** An icon button's touch target, as Material sets it. */
        const val TOUCH_TARGET = 48

        /** The server's first five categories, every one played, as the Play screen names them in Cyrillic. */
        const val LONG_SELECTION = "Храна, Начин живота, Етика, Супермоћи, Апсурдно"

        /** Play's bar with [categories] played, and a dot on the account icon while [news]. */
        @Composable
        private fun PlayBar(
            actions: Actions,
            categories: String,
            news: Boolean,
        ) {
            PlayTopBar(onHome = actions.record("home"), onAccount = actions.record("account"), news = news) {
                CategoriesPlayed(text = categories, enabled = true, onClick = actions.record("categories"))
            }
        }

        val BARS =
            listOf(
                // Home, the categories and the account icon, one icon on each side.
                Bar(
                    name = "Play's",
                    icons = { listOf(it.home, it.account) },
                    texts = { listOf(it.allCategories) },
                    taps = listOf("home", "account", "categories"),
                    draw = { actions -> PlayBar(actions, LocalStrings.current.allCategories, news = false) },
                ),
                // Every category played, as long a line as five names make: cut short in the middle,
                // never the icons, and never a second line.
                Bar(
                    name = "Play's, with a long selection",
                    icons = { listOf(it.home, it.account) },
                    texts = { listOf(LONG_SELECTION) },
                    taps = listOf("home", "account", "categories"),
                    cutShort = true,
                    draw = { actions -> PlayBar(actions, LONG_SELECTION, news = false) },
                ),
                // A moderator decided a question of the player's: a dot on the account icon, which a
                // screen reader hears in its name, and nothing else changes.
                Bar(
                    name = "Play's, with news",
                    icons = { listOf(it.home, it.notice.accountWithNews.fill(it.account)) },
                    texts = { listOf(it.allCategories) },
                    taps = listOf("home", "account", "categories"),
                    draw = { actions -> PlayBar(actions, LocalStrings.current.allCategories, news = true) },
                ),
                // The dot takes no width of the categories': a long selection is cut as it is without it
                // (`the dot leaves a long selection its room`).
                Bar(
                    name = "Play's, with news and a long selection",
                    icons = { listOf(it.home, it.notice.accountWithNews.fill(it.account)) },
                    texts = { listOf(LONG_SELECTION) },
                    taps = listOf("home", "account", "categories"),
                    cutShort = true,
                    draw = { actions -> PlayBar(actions, LONG_SELECTION, news = true) },
                ),
                Bar(
                    name = "Home's, with news",
                    icons = { listOf(it.notice.accountWithNews.fill(it.account)) },
                    texts = { emptyList() },
                    taps = listOf("account"),
                    draw = { HomeTopBar(onAccount = it.record("account"), news = true) },
                ),
                Bar(
                    name = "Account's",
                    icons = { listOf(it.back, it.shopScreen.title, it.aboutScreen.title) },
                    texts = { emptyList() },
                    taps = listOf("back", "shop", "about"),
                    draw = {
                        AccountTopBar(
                            onBack = it.record("back"),
                            onAbout = it.record("about"),
                            onShop = it.record("shop"),
                        )
                    },
                ),
                Bar(
                    name = "the Auth page's, Submit's, the Categories screen's and the About screen's",
                    icons = { listOf(it.back) },
                    texts = { emptyList() },
                    taps = listOf("back"),
                    draw = { BackTopBar(onBack = it.record("back")) },
                ),
            )
    }
}

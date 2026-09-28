package io.ntole.wyr.shop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.shop.Shop
import io.ntole.wyr.core.domain.shop.ShopTheme
import io.ntole.wyr.descriptions
import io.ntole.wyr.everyText
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.GameTheme
import io.ntole.wyr.theme.GameThemes
import io.ntole.wyr.theme.PageSurface
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shop (CLAUDE.md §8d, *The shop*) drawn off screen in both of the game's modes and every
 * language: every theme on its card with a preview named for it, the button each has, a guest told to
 * register, the dialog before a purchase, and the line that more is coming; and each theme's page and
 * art, drawn as a page of the game would be.
 */
class ShopScreenDrawTest {
    @Test
    fun `every theme shows on its card with a preview and its one button`() {
        listOf(false, true).forEach { dark ->
            Language.entries.forEach { language ->
                val all = stringsOf(language)
                val strings = all.shopScreen
                val scene = scene(ShopState(shop = REGISTERED), language, dark = dark)
                try {
                    val texts = scene.everyText()
                    assertTrue(strings.title in texts, "$language: $texts")
                    assertTrue(strings.comingSoon == texts.last(), "$language: more is coming, last: $texts")
                    GameThemes.ALL.forEach { theme ->
                        val name = themeName(theme.id, strings)
                        assertTrue(name in texts, "$language: ${theme.id} has no card")
                        assertTrue(
                            strings.preview.fill(name) in scene.descriptions(),
                            "$language: ${theme.id}'s preview",
                        )
                    }
                    // The game's own worn, Neon night owned, the rest on sale at their price.
                    assertEquals(1, texts.count { it == strings.active }, "$language")
                    assertEquals(1, texts.count { it == strings.apply }, "$language")
                    val price = strings.buy.fill(all.points.fill(PRICE))
                    assertEquals(3, scene.descriptions().count { it == price }, "$language")
                    assertFalse(strings.registerToBuy in texts, "$language: registered")
                    assertTrue(all.points.fill(POINTS) in scene.descriptions(), "$language: the points")
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun `a guest is told to register and a player short of points is told so`() {
        Language.entries.forEach { language ->
            val all = stringsOf(language)
            val guest = scene(ShopState(shop = REGISTERED.copy(registered = false)), language)
            try {
                assertTrue(all.shopScreen.registerToBuy in guest.texts(), "$language: ${guest.texts()}")
                assertTrue(all.accountScreens.openAuth in guest.texts(), "$language: the way to register")
            } finally {
                guest.close()
            }
            val short = scene(ShopState(shop = REGISTERED.copy(points = 10)), language)
            try {
                assertTrue(all.accountScreens.notEnoughPoints in short.texts(), "$language: ${short.texts()}")
            } finally {
                short.close()
            }
        }
    }

    @Test
    fun `the dialog before a purchase shows the theme larger and asks`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).shopScreen
            val actions = RecordingActions()
            val scene = scene(ShopState(shop = REGISTERED, confirming = "OCEAN"), language, actions = actions)
            try {
                assertTrue(strings.confirmBuy.fill(strings.ocean) in scene.texts(), "$language: ${scene.texts()}")
                // The card's preview and the dialog's.
                assertEquals(2, scene.descriptions().count { it == strings.preview.fill(strings.ocean) }, "$language")
                scene.tap(stringsOf(language).cancel)
            } finally {
                scene.close()
            }
            assertEquals(listOf("cancel"), actions.asked, "$language")
        }
    }

    @Test
    fun `a read that failed says why with Try again`() {
        Language.entries.forEach { language ->
            val all = stringsOf(language)
            val actions = RecordingActions()
            val scene = scene(ShopState(readFailure = ShopFailure(DomainError.NETWORK)), language, actions = actions)
            try {
                assertTrue(all.accountScreens.offline in scene.texts(), "$language: ${scene.texts()}")
                scene.tap(all.tryAgain)
            } finally {
                scene.close()
            }
            assertEquals(listOf("refresh"), actions.asked, "$language")
        }
    }

    /**
     * Each theme draws its page in its own colour, and every one but the game's own draws its art over
     * it: some pixel of a page the size of a phone's is one of the art's colours.
     */
    @Test
    fun `each theme draws its page and its art`() {
        GameThemes.ALL.forEach { theme ->
            val pixels = pageOf(theme)
            val colors = theme.colors(darkMode = false)
            assertEquals(colors.pageBackground, pixels.first(), "${theme.id}: the page's top left")
            val art = theme.art.colors.toSet()
            if (art.isEmpty()) {
                assertTrue(pixels.all { it == colors.pageBackground }, "${theme.id}: the game's own page is plain")
            } else {
                assertTrue(pixels.any { it in art }, "${theme.id}: no pixel of its art")
            }
        }
    }

    /** Every pixel of a page in [theme], drawn at a phone's size, from the top left, row by row. */
    private fun pageOf(theme: GameTheme): List<Color> {
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                WyrTheme(theme = theme, darkTheme = false) { PageSurface { Box(Modifier.fillMaxSize()) } }
            }
        try {
            val pixels = scene.render().toComposeImageBitmap().toPixelMap()
            return (0 until pixels.height).flatMap { y -> (0 until pixels.width).map { x -> pixels[x, y] } }
        } finally {
            scene.close()
        }
    }

    private fun scene(
        state: ShopState,
        language: Language,
        dark: Boolean = false,
        actions: ShopActions = RecordingActions(),
    ): ImageComposeScene =
        ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            WyrTheme(darkTheme = dark) {
                WyrStrings(language) {
                    ShopScreen(
                        state = state,
                        actions = actions,
                        worn = GameThemes.Default,
                        onWear = {},
                        onOpenAuth = {},
                    )
                }
            }
        }.also { it.render() }

    /** What the screen asked for, in order. */
    private class RecordingActions : ShopActions {
        val asked = mutableListOf<String>()

        override fun refresh() {
            asked += "refresh"
        }

        override fun askToBuy(themeId: String) {
            asked += "ask $themeId"
        }

        override fun cancelBuy() {
            asked += "cancel"
        }

        override fun buy() {
            asked += "buy"
        }

        override fun boughtWorn() {
            asked += "worn"
        }
    }

    private companion object {
        /** An iPhone SE (667 high) less its status bar (20) and the top bar above the screen (48). */
        const val WIDTH = 375
        const val HEIGHT = 599
        const val PRICE = 220
        const val POINTS = 500

        val REGISTERED =
            Shop(
                themes =
                    listOf(
                        ShopTheme("NEON_NIGHT", PRICE, owned = true),
                        ShopTheme("OCEAN", PRICE, owned = false),
                        ShopTheme("FOREST", PRICE, owned = false),
                        ShopTheme("SUNSET", PRICE, owned = false),
                    ),
                points = POINTS,
                registered = true,
            )
    }
}

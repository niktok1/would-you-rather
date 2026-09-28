package io.ntole.wyr.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import io.ntole.wyr.navigation.Navigator
import io.ntole.wyr.navigation.Screen
import io.ntole.wyr.navigation.ScreenTransitions
import io.ntole.wyr.renderAt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The game's own theme's art (CLAUDE.md §5b, *Backgrounds*, [ThemeArt.QuestionMarks]), drawn as the app
 * draws the page under the whole window ([WholePageArt]), off screen at a phone's size and a desktop
 * window's, light and dark: where each tint is, by the pixels, and that the page is recorded once,
 * however many frames of a screen's change move over it.
 */
class ClassicArtDrawTest {
    /** Upright, card A's pink at the top, card B's amber at the bottom, and the plain page between. */
    @Test
    fun `the wash is pink at the top and amber at the bottom on a phone`() {
        LOOKS.forEach { (dark, colors, art) ->
            val pixels = pixelsOf(dark, PHONE_WIDTH, PHONE_HEIGHT)
            val middle = PHONE_WIDTH / 2
            assertNear(art.washA, pixels[middle, 0], "top, dark $dark")
            assertNear(art.washB, pixels[middle, PHONE_HEIGHT - 1], "bottom, dark $dark")
            assertNear(colors.pageBackground, pixels[middle, PHONE_HEIGHT / 2], "middle, dark $dark")
        }
    }

    /** Where the Play screen stands its cards side by side the wash runs with them: pink left, amber right. */
    @Test
    fun `the wash is pink on the left and amber on the right on a wide window`() {
        LOOKS.forEach { (dark, colors, art) ->
            val pixels = pixelsOf(dark, WINDOW_WIDTH, WINDOW_HEIGHT)
            val middle = WINDOW_HEIGHT / 2
            assertNear(art.washA, pixels[0, middle], "left, dark $dark")
            assertNear(art.washB, pixels[WINDOW_WIDTH - 1, middle], "right, dark $dark")
            assertNear(colors.pageBackground, pixels[WINDOW_WIDTH / 2, middle], "middle, dark $dark")
        }
    }

    /**
     * The question marks are there by the edges, faint, and not down the middle, where the cards are: a
     * column near the left edge differs from the middle's somewhere, and no pixel is further from the
     * page than a mark over the strongest wash.
     */
    @Test
    fun `faint question marks stand by the edges`() {
        LOOKS.forEach { (dark, colors, art) ->
            val pixels = pixelsOf(dark, PHONE_WIDTH, PHONE_HEIGHT)
            val marked =
                (0 until PHONE_HEIGHT).count { y ->
                    pixels[PHONE_WIDTH * 12 / 100, y] !=
                        pixels[PHONE_WIDTH / 2, y]
                }
            assertTrue(marked > 0, "no question mark by the left edge, dark $dark")
            val strongest = art.colors.maxOf { distance(it, colors.pageBackground) } + 2
            val all = (0 until PHONE_HEIGHT).flatMap { y -> (0 until PHONE_WIDTH).map { x -> pixels[x, y] } }
            assertTrue(
                all.all { distance(it, colors.pageBackground) <= strongest },
                "a tint stronger than the art's, dark $dark",
            )
        }
    }

    /** Static: recorded once, and drawn again from that record through every frame of a screen's change. */
    @Test
    fun `the page is recorded once whatever moves over it`() {
        var recorded = 0
        val navigator = Navigator()
        val scene =
            ImageComposeScene(width = PHONE_WIDTH, height = PHONE_HEIGHT, density = Density(1f)) {
                WyrTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize()) {
                        WholePageArt(Modifier.matchParentSize(), drawn = { recorded++ })
                        ScreenTransitions(navigator, Modifier.fillMaxSize()) { screen -> Text(screen.key) }
                    }
                }
            }
        try {
            scene.renderAt(0)
            assertEquals(1, recorded, "recorded as first drawn")
            navigator.open(Screen.Account)
            (1..30).forEach { frame -> scene.renderAt(frame * FRAME) }
            assertEquals(1, recorded, "recorded again while a screen came in over it")
        } finally {
            scene.close()
        }
    }

    private fun pixelsOf(
        dark: Boolean,
        width: Int,
        height: Int,
    ): PixelMap {
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                WyrTheme(darkTheme = dark) { WholePageArt(Modifier.fillMaxSize()) }
            }
        return try {
            scene.render().toComposeImageBitmap().toPixelMap()
        } finally {
            scene.close()
        }
    }

    /** How far apart [a] and [b] are, in levels out of 255, on the channel they differ most on. */
    private fun distance(
        a: Color,
        b: Color,
    ): Int {
        val (x, y) = a.toArgb() to b.toArgb()
        return listOf(16, 8, 0).maxOf { shift -> abs((x shr shift and 0xFF) - (y shr shift and 0xFF)) }
    }

    private fun assertNear(
        expected: Color,
        actual: Color,
        what: String,
    ) = assertTrue(distance(expected, actual) <= 2, "$what: $actual, not $expected")

    private data class Look(
        val dark: Boolean,
        val colors: WyrColors,
        val art: ThemeArt.QuestionMarks,
    )

    private companion object {
        const val PHONE_WIDTH = 375
        const val PHONE_HEIGHT = 667
        const val WINDOW_WIDTH = 800
        const val WINDOW_HEIGHT = 600
        const val FRAME = 1_000_000_000L / 60
        val LOOKS =
            listOf(false, true).map { dark ->
                Look(
                    dark,
                    GameThemes.Default.colors(dark),
                    GameThemes.Default.art(dark) as ThemeArt.QuestionMarks,
                )
            }
    }
}

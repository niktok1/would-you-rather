package io.ntole.wyr.points

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ntole.wyr.CountedBy
import io.ntole.wyr.Recompositions
import io.ntole.wyr.descriptions
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.nodes
import io.ntole.wyr.renderAt
import io.ntole.wyr.theme.WyrMotion
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The coin's bump (CLAUDE.md §5b, *Motion*), stepped frame by frame on the scene's clock at 60 a
 * second: points that go up bump the coin, larger and back, in its layer, the amount's place and its
 * number unmoved and no frame composing anything; the first amount shown, and one that falls, bump
 * nothing.
 */
class CoinBumpDrawTest {
    @Test
    fun `the coin bumps as the points go up and only then`() {
        val recompositions = Recompositions()
        var points by mutableIntStateOf(POINTS)
        var time = 0L
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                CountedBy(recompositions) {
                    WyrTheme(darkTheme = false) {
                        WyrStrings(Language.DEFAULT) {
                            Box(Modifier.fillMaxSize().padding(INSET.dp)) { PointsAmount(points, fontSize = TEXT.sp) }
                        }
                    }
                }
            }

        fun until(end: Long) {
            while (time < end) {
                time += FRAME
                scene.renderAt(time)
            }
        }
        try {
            scene.renderAt(0)
            val place = scene.amount()
            val rest = scene.coinHeight(place, time)
            until(WHOLE / 2)
            assertEquals(rest, scene.coinHeight(place, time), "the first amount bumped")

            points = POINTS - 1
            var start = time + FRAME
            until(start + WHOLE / 2)
            assertEquals(rest, scene.coinHeight(scene.amount(), time), "points that fell bumped")

            points = POINTS
            start = time + FRAME
            until(start + 2 * FRAME)
            val before = recompositions.scopesEntered
            until(start + WHOLE / 2)
            assertEquals(before, recompositions.scopesEntered, "a frame of the bump composed")
            val bumped = scene.coinHeight(place, time)
            assertTrue(bumped >= rest * 5 / 4, "the coin at its largest: $bumped of $rest")
            assertEquals(place, scene.amount(), "the amount moved")

            until(start + WHOLE + 2 * FRAME)
            assertEquals(rest, scene.coinHeight(place, time), "the coin where it rests")
            recompositions.assertCounting(scene, time)
        } finally {
            scene.close()
        }
    }

    /** Where the amount stands, the coin and its number. */
    private fun ImageComposeScene.amount(): Rect = nodes().single { it.descriptions.any(SHOWN::contains) }.boundsInRoot

    /**
     * How high the coin at the start of the amount at [place] is drawn at [now]: the rows of pixels at
     * least half opaque from the left edge to the coin's box's end, short of the number after it.
     */
    private fun ImageComposeScene.coinHeight(
        place: Rect,
        now: Long,
    ): Int {
        val pixels = render(now).toComposeImageBitmap().toPixelMap()
        val columns = 0 until place.left.toInt() + place.height.toInt() - REACH
        val rows = (0 until pixels.height).filter { y -> columns.any { x -> pixels[x, y].alpha >= 0.5f } }
        return if (rows.isEmpty()) 0 else rows.last() - rows.first() + 1
    }

    private companion object {
        const val WIDTH = 200
        const val HEIGHT = 100
        const val INSET = 20
        const val TEXT = 20
        const val REACH = 4
        const val POINTS = 42
        const val FRAME = 1_000_000_000L / 60
        const val WHOLE = WyrMotion.COIN_BUMP_MILLIS * 1_000_000L
        val SHOWN = listOf(POINTS, POINTS - 1).map { stringsOf(Language.DEFAULT).points.fill(it) }
    }
}

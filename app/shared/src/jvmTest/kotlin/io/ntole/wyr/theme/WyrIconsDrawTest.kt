package io.ntole.wyr.theme

import androidx.compose.foundation.Image
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The theme's hand-drawn icons (CLAUDE.md §5b), each drawn off screen at its own size. Nothing but
 * pixels can tell an icon that draws from one that is empty or the same as another, so this counts
 * them.
 */
class WyrIconsDrawTest {
    @Test
    fun `every icon is the theme's size and draws a figure`() {
        ICONS.forEach { (name, icon) ->
            assertEquals(24.dp, icon.defaultWidth, name)
            assertEquals(24.dp, icon.defaultHeight, name)
            val painted = painted(icon).count { it }
            assertTrue(painted > 0, "$name draws nothing")
            assertTrue(painted < SIZE * SIZE, "$name fills its whole square")
        }
    }

    @Test
    fun `no two icons draw the same`() {
        val drawn = ICONS.map { (name, icon) -> name to painted(icon) }
        drawn.forEachIndexed { index, (name, pixels) ->
            drawn.drop(index + 1).forEach { (other, otherPixels) ->
                assertFalse(pixels.contentEquals(otherPixels), "$name draws the same as $other")
            }
        }
    }

    /** Each filled thumb is its outline's size exactly, with the hand's inside painted too. */
    @Test
    fun `each filled thumb covers its outline and its inside`() {
        listOf(
            Triple("thumb up", WyrIcons.ThumbUp to WyrIcons.ThumbUpFilled, pixelAt(x = 16, y = 14)),
            // Turned over top to bottom, so the hand's inside is as far above the middle as it was below.
            Triple("thumb down", WyrIcons.ThumbDown to WyrIcons.ThumbDownFilled, pixelAt(x = 16, y = 9)),
        ).forEach { (name, icons, inside) ->
            val outline = painted(icons.first)
            val filled = painted(icons.second)

            outline.indices.forEach { pixel ->
                if (outline[pixel]) assertTrue(filled[pixel], "the filled $name leaves pixel $pixel of the outline out")
            }
            assertFalse(outline[inside], "the $name's outline paints the hand's inside")
            assertTrue(filled[inside], "the filled $name leaves the hand's inside empty")
        }
    }

    /**
     * The thumb down is the thumb up turned over, top to bottom: the same pixels mirrored, but for a few
     * at the edges of its strokes, where the rasteriser's rounding is not mirrored with them.
     */
    @Test
    fun `the thumb down is the thumb up turned over`() {
        val up = painted(WyrIcons.ThumbUp)
        val down = painted(WyrIcons.ThumbDown)

        val turned = BooleanArray(SIZE * SIZE) { index -> up[pixelAt(x = index % SIZE, y = SIZE - 1 - index / SIZE)] }
        val differing = turned.indices.count { turned[it] != down[it] }
        assertTrue(differing * 20 <= down.count { it }, "$differing pixels differ")
    }

    /** The coin's face is a full disc, and its mark a rim and a ring on it, with the face's middle clear of both. */
    @Test
    fun `the coin's face is a disc under the ring of its mark`() {
        val face = painted(WyrIcons.CoinFace)
        val mark = painted(WyrIcons.CoinMark)
        val middle = pixelAt(x = SIZE / 2, y = SIZE / 2)

        assertTrue(face[middle], "the face is a full disc")
        assertFalse(mark[middle], "the ring leaves the face's middle to show")
        // The ring, well inside the rim, lies on the face.
        val ring = pixelAt(x = SIZE / 2 + 5, y = SIZE / 2)
        assertTrue(mark[ring] && face[ring], "the ring is drawn on the face")
    }

    private fun pixelAt(
        x: Int,
        y: Int,
    ): Int = y * SIZE + x

    /** Which of [icon]'s pixels are painted at all, row by row, drawn untinted at one pixel a dp. */
    private fun painted(icon: ImageVector): BooleanArray {
        val scene =
            ImageComposeScene(width = SIZE, height = SIZE, density = Density(1f)) {
                Image(imageVector = icon, contentDescription = null)
            }
        return try {
            val pixels =
                scene
                    .render()
                    .toComposeImageBitmap()
                    .toPixelMap()
            BooleanArray(SIZE * SIZE) { index -> pixels[index % SIZE, index / SIZE].alpha > 0f }
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val SIZE = 24

        val ICONS =
            listOf(
                "Home" to WyrIcons.Home,
                "Account" to WyrIcons.Account,
                "Back" to WyrIcons.Back,
                "Skip" to WyrIcons.Skip,
                "ChevronDown" to WyrIcons.ChevronDown,
                "ThumbUp" to WyrIcons.ThumbUp,
                "ThumbUpFilled" to WyrIcons.ThumbUpFilled,
                "ThumbDown" to WyrIcons.ThumbDown,
                "ThumbDownFilled" to WyrIcons.ThumbDownFilled,
                "CoinFace" to WyrIcons.CoinFace,
                "CoinMark" to WyrIcons.CoinMark,
                "Globe" to WyrIcons.Globe,
                "Players" to WyrIcons.Players,
                "More" to WyrIcons.More,
            )
    }
}

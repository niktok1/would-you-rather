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

    /** The filled heart is the outline's size exactly, with its inside painted too. */
    @Test
    fun `the filled heart covers the outline and its inside`() {
        val outline = painted(WyrIcons.Heart)
        val filled = painted(WyrIcons.HeartFilled)

        outline.indices.forEach { pixel ->
            if (outline[pixel]) assertTrue(filled[pixel], "the filled heart leaves pixel $pixel of the outline out")
        }
        // The heart's middle, well inside the outline's stroke.
        val middle = SIZE / 2 * SIZE + SIZE / 2
        assertFalse(outline[middle], "the outline paints its own middle")
        assertTrue(filled[middle], "the filled heart leaves its middle empty")
    }

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
                "Heart" to WyrIcons.Heart,
                "HeartFilled" to WyrIcons.HeartFilled,
            )
    }
}

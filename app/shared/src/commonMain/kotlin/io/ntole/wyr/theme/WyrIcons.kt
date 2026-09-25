package io.ntole.wyr.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Every icon in the app, drawn here by hand, so no icon library is needed (CLAUDE.md §5b): a few
 * lines each on a 24 by 24 grid, outlined in one stroke width with round ends, and the heart filled
 * besides for a question the player likes.
 *
 * They are drawn in black and carry no colour of their own: `Icon` tints the whole of one, so a
 * screen gives it a colour from [WyrColors] and each icon follows the light and dark themes as text
 * does.
 */
object WyrIcons {
    /** Home: a house with a door, for the way back to the Home screen. */
    val Home: ImageVector by lazy {
        icon("Home") {
            outline {
                // The roof, then the walls with the door cut into them.
                moveTo(3f, 11f)
                lineTo(12f, 4f)
                lineTo(21f, 11f)
                moveTo(6f, 9.5f)
                verticalLineTo(20f)
                horizontalLineTo(10f)
                verticalLineTo(15f)
                horizontalLineTo(14f)
                verticalLineTo(20f)
                horizontalLineTo(18f)
                verticalLineTo(9.5f)
            }
        }
    }

    /** Account: a head and shoulders, for the player's Account screen. */
    val Account: ImageVector by lazy {
        icon("Account") {
            outline {
                // The head, a circle of radius 3.5 about (12, 8.5), in two half turns.
                moveTo(8.5f, 8.5f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 15.5f, y1 = 8.5f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 8.5f, y1 = 8.5f)
                close()
                // The shoulders.
                moveTo(5f, 20f)
                curveTo(5f, 16.5f, 8f, 14f, 12f, 14f)
                curveTo(16f, 14f, 19f, 16.5f, 19f, 20f)
            }
        }
    }

    /** Back: an arrow pointing left, for the way back to the screen before. */
    val Back: ImageVector by lazy {
        icon("Back") {
            outline {
                moveTo(19f, 12f)
                horizontalLineTo(5f)
                moveTo(11f, 6f)
                lineTo(5f, 12f)
                lineTo(11f, 18f)
            }
        }
    }

    /**
     * Skip: a triangle pointing right against a bar, a music player's "next", for going past a
     * question without answering it (CLAUDE.md §8d, *The Play screen*).
     */
    val Skip: ImageVector by lazy {
        icon("Skip") {
            outline {
                moveTo(6f, 6f)
                lineTo(15f, 12f)
                lineTo(6f, 18f)
                close()
                moveTo(18f, 6f)
                verticalLineTo(18f)
            }
        }
    }

    /** A small chevron pointing down, beside a value a tap changes: the categories played. */
    val ChevronDown: ImageVector by lazy {
        icon("ChevronDown") {
            outline {
                moveTo(8f, 10f)
                lineTo(12f, 14f)
                lineTo(16f, 10f)
            }
        }
    }

    /** A heart's outline, for a question the player does not like. */
    val Heart: ImageVector by lazy { icon("Heart") { outline { heart() } } }

    /** A filled heart, the outline's size exactly, for a question the player likes. */
    val HeartFilled: ImageVector by lazy {
        icon("HeartFilled") {
            path(fill = SolidColor(Color.Black)) { heart() }
            outline { heart() }
        }
    }

    /** A heart: two round lobes meeting in a dip at the top, and a point at the bottom. */
    private fun PathBuilder.heart() {
        moveTo(12f, 20f)
        curveTo(12f, 20f, 3f, 14.5f, 3f, 8.5f)
        curveTo(3f, 5.5f, 5.5f, 3.5f, 8f, 3.5f)
        curveTo(9.8f, 3.5f, 11.2f, 4.5f, 12f, 6f)
        curveTo(12.8f, 4.5f, 14.2f, 3.5f, 16f, 3.5f)
        curveTo(18.5f, 3.5f, 21f, 5.5f, 21f, 8.5f)
        curveTo(21f, 14.5f, 12f, 20f, 12f, 20f)
        close()
    }

    private fun icon(
        name: String,
        draw: ImageVector.Builder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = SIZE.dp,
                defaultHeight = SIZE.dp,
                viewportWidth = SIZE,
                viewportHeight = SIZE,
            ).apply(draw)
            .build()

    /** One stroke of the icons' width, with round ends and corners. */
    private fun ImageVector.Builder.outline(draw: PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        )
    }

    /** The grid every icon is drawn on, which is also its size in dp. */
    private const val SIZE = 24f

    private const val STROKE = 2f
}

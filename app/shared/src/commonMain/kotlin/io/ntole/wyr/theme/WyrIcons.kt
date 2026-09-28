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
 * lines each on a 24 by 24 grid, outlined in one stroke width with round ends, the thumbs filled
 * besides for a question the player likes or dislikes, and the coin's face filled.
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

    /** A thumb up's outline, for a question the player does not like (CLAUDE.md §8d, *Reactions*). */
    val ThumbUp: ImageVector by lazy { icon("ThumbUp") { outline { thumb(down = false) } } }

    /** A thumb up filled, the outline's size exactly, for a question the player likes. */
    val ThumbUpFilled: ImageVector by lazy { filled("ThumbUpFilled") { thumb(down = false) } }

    /** A thumb down's outline, the thumb up turned over, for a question the player does not dislike. */
    val ThumbDown: ImageVector by lazy { icon("ThumbDown") { outline { thumb(down = true) } } }

    /** A thumb down filled, the outline's size exactly, for a question the player dislikes. */
    val ThumbDownFilled: ImageVector by lazy { filled("ThumbDownFilled") { thumb(down = true) } }

    /**
     * A coin's face, a full disc, the points' sign wherever the game shows them (CLAUDE.md §5b). Drawn
     * under [CoinMark] in a colour of its own, so the coin is two colours while each icon is tinted
     * whole, as every other is.
     */
    val CoinFace: ImageVector by lazy {
        icon("CoinFace") { path(fill = SolidColor(Color.Black)) { circle(COIN_RADIUS) } }
    }

    /** A coin's rim and the ring stamped in its face, drawn over [CoinFace]. */
    val CoinMark: ImageVector by lazy {
        icon("CoinMark") {
            outline {
                circle(COIN_RADIUS)
                circle(COIN_RING_RADIUS)
            }
        }
    }

    /** A globe, for the language menu, found by its look whatever language the screen is in. */
    val Globe: ImageVector by lazy {
        icon("Globe") {
            outline {
                circle(COIN_RADIUS)
                // A meridian, an ellipse in two half turns, and the equator.
                moveTo(12f, 3f)
                arcTo(4f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 21f)
                arcTo(4f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 3f)
                moveTo(3f, 12f)
                horizontalLineTo(21f)
            }
        }
    }

    /** Two players, one behind the other, for how many players answered (My questions' table). */
    val Players: ImageVector by lazy {
        icon("Players") {
            outline {
                // The one in front, as Account's player, smaller and to the left.
                moveTo(5.8f, 8.5f)
                arcTo(3.2f, 3.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12.2f, y1 = 8.5f)
                arcTo(3.2f, 3.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 5.8f, y1 = 8.5f)
                close()
                moveTo(3f, 19.5f)
                curveTo(3f, 16f, 5.7f, 13.8f, 9f, 13.8f)
                curveTo(12.3f, 13.8f, 15f, 16f, 15f, 19.5f)
                // The one behind, to the right: a head, and a shoulder showing past the one in front.
                moveTo(13.9f, 7.5f)
                arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 19.1f, y1 = 7.5f)
                arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 13.9f, y1 = 7.5f)
                close()
                moveTo(17f, 13.2f)
                curveTo(19.4f, 13.6f, 21f, 15.6f, 21f, 18.5f)
            }
        }
    }

    /** Info: an i in a circle, for the About screen, opened from the Account screen's top bar. */
    val Info: ImageVector by lazy {
        icon("Info") {
            outline {
                circle(COIN_RADIUS)
                // The i's dot, a stroke so short its round ends make it a dot, and its stem.
                moveTo(12f, 7.6f)
                lineTo(12f, 8f)
                moveTo(12f, 11f)
                verticalLineTo(16.5f)
            }
        }
    }

    /**
     * More: three dots one over the other, for the Play screen's menu about the question on screen
     * (CLAUDE.md §8d, *The Play screen*, *Reports*): report it, or hide it or its author.
     */
    val More: ImageVector by lazy {
        icon("More") {
            path(fill = SolidColor(Color.Black)) {
                listOf(5f, 12f, 19f).forEach { y -> dot(x = SIZE / 2, y = y) }
            }
        }
    }

    /**
     * Copy: two sheets, one over the other, for copying the player's account id (CLAUDE.md §8d,
     * *About*).
     */
    val Copy: ImageVector by lazy {
        icon("Copy") {
            outline {
                // The sheet on top, then the edges of the one under it that show.
                moveTo(9f, 9f)
                horizontalLineTo(20f)
                verticalLineTo(20f)
                horizontalLineTo(9f)
                close()
                moveTo(15f, 9f)
                verticalLineTo(4f)
                horizontalLineTo(4f)
                verticalLineTo(15f)
                horizontalLineTo(9f)
            }
        }
    }

    /** Plus: a cross of two strokes, for adding a question to My questions (CLAUDE.md §8d, *The Account screen*). */
    val Plus: ImageVector by lazy {
        icon("Plus") {
            outline {
                moveTo(12f, 5f)
                verticalLineTo(19f)
                moveTo(5f, 12f)
                horizontalLineTo(19f)
            }
        }
    }

    /** A small chevron pointing right, on a row that opens more of itself: a question of My questions. */
    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight") {
            outline {
                moveTo(10f, 8f)
                lineTo(14f, 12f)
                lineTo(10f, 16f)
            }
        }
    }

    /**
     * Share: three rings joined by two strokes, one on the left linked to two on the right, for sharing a
     * question (CLAUDE.md §8d, *Sharing*).
     */
    val Share: ImageVector by lazy {
        icon("Share") {
            outline {
                ring(x = 18f, y = 5f)
                ring(x = 6f, y = 12f)
                ring(x = 18f, y = 19f)
                // From the ring on the left to each on the right, their edges only.
                moveTo(8.6f, 10.5f)
                lineTo(15.4f, 6.5f)
                moveTo(8.6f, 13.5f)
                lineTo(15.4f, 17.5f)
            }
        }
    }

    /**
     * A thumb up, or turned over top to bottom for a thumb down: the cuff on the left, and the hand
     * beside it, its thumb pointing up out of it, or down.
     */
    private fun PathBuilder.thumb(down: Boolean) {
        fun y(value: Float) = if (down) SIZE - value else value

        // The cuff.
        moveTo(3f, y(10.5f))
        lineTo(7f, y(10.5f))
        lineTo(7f, y(20f))
        lineTo(3f, y(20f))
        close()
        // The hand: up the thumb, round its tip and down, across the fingers and back along the palm.
        moveTo(7f, y(10.5f))
        lineTo(10.2f, y(4.2f))
        curveTo(10.6f, y(3.4f), 11.5f, y(2.9f), 12.4f, y(3.1f))
        curveTo(13.5f, y(3.4f), 14.1f, y(4.5f), 13.8f, y(5.6f))
        lineTo(12.9f, y(9f))
        lineTo(18.8f, y(9f))
        curveTo(20.1f, y(9f), 21.1f, y(10.2f), 20.9f, y(11.5f))
        lineTo(19.9f, y(18.4f))
        curveTo(19.7f, y(19.4f), 18.9f, y(20f), 17.9f, y(20f))
        lineTo(7f, y(20f))
        close()
    }

    /** A circle of [radius] about the grid's middle, in two half turns. */
    private fun PathBuilder.circle(radius: Float) {
        val middle = SIZE / 2
        moveTo(middle - radius, middle)
        arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = middle + radius, y1 = middle)
        arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = middle - radius, y1 = middle)
        close()
    }

    /** A ring of [RING_RADIUS] about ([x], [y]), in two half turns, for [Share]. */
    private fun PathBuilder.ring(
        x: Float,
        y: Float,
    ) {
        moveTo(x - RING_RADIUS, y)
        arcTo(RING_RADIUS, RING_RADIUS, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = x + RING_RADIUS, y1 = y)
        arcTo(RING_RADIUS, RING_RADIUS, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = x - RING_RADIUS, y1 = y)
        close()
    }

    /** A dot of [DOT_RADIUS] about ([x], [y]), in two half turns. */
    private fun PathBuilder.dot(
        x: Float,
        y: Float,
    ) {
        moveTo(x - DOT_RADIUS, y)
        arcTo(DOT_RADIUS, DOT_RADIUS, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = x + DOT_RADIUS, y1 = y)
        arcTo(DOT_RADIUS, DOT_RADIUS, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = x - DOT_RADIUS, y1 = y)
        close()
    }

    /** [draw]'s figure filled and outlined, the outline's size exactly. */
    private fun filled(
        name: String,
        draw: PathBuilder.() -> Unit,
    ): ImageVector =
        icon(name) {
            path(fill = SolidColor(Color.Black), pathBuilder = draw)
            outline(draw)
        }

    /**
     * Shop: a bag with a handle, for the way to the shop (CLAUDE.md §8d, *The shop*), on the Account
     * screen's top bar.
     */
    val Shop: ImageVector by lazy {
        icon("Shop") {
            outline {
                // The bag, a little wider at the bottom, then its handle over the opening.
                moveTo(5f, 8f)
                horizontalLineTo(19f)
                lineTo(20f, 20f)
                horizontalLineTo(4f)
                close()
                moveTo(9f, 8f)
                verticalLineTo(6.5f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15f, y1 = 6.5f)
                verticalLineTo(8f)
            }
        }
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

    /** A coin's rim, and the globe's outline, about the grid's middle. */
    private const val COIN_RADIUS = 9f

    /** The ring stamped in a coin's face. */
    private const val COIN_RING_RADIUS = 5f

    /** Each of [More]'s dots, a little wider than a stroke. */
    private const val DOT_RADIUS = 2f

    /** Each of [Share]'s rings. */
    private const val RING_RADIUS = 3f
}

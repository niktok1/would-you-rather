package io.ntole.wyr.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The art a theme draws on the page, behind every screen (CLAUDE.md §8d, *The shop*): a few shapes
 * drawn by hand, as the icons are, so no image is shipped. Each is laid out in fractions of the page it
 * is drawn on, so it fits a phone, a tablet and a preview alike, and is the same every time it is drawn:
 * the stars and bubbles stand where a fixed sequence puts them, never at random.
 *
 * Its colours are the theme's, and each keeps the page's text at AA on it (`WyrContrastTest`), so it
 * never has to stay out of a text's way.
 */
sealed interface ThemeArt {
    /** Every colour the art shows on the page, as it shows there, one drawn over another included. */
    val colors: List<Color>

    /** Draws the art over the whole of this scope, its page already drawn. */
    fun DrawScope.draw()

    /** The game's own theme: the page plain. */
    data object None : ThemeArt {
        override val colors: List<Color> = emptyList()

        override fun DrawScope.draw() = Unit
    }

    /**
     * The game's own theme (CLAUDE.md §5b, *Backgrounds*): a faint wash of card A's pink toward the top
     * and of card B's amber toward the bottom, the plain [page] across the middle, echoing the two cards,
     * and a few faint question marks by the edges. Where the window is wide enough for the Play screen to
     * stand its cards side by side ([WyrDimens.wideLayoutMinWidth], and wider than tall) the wash runs
     * from left to right with them, and the marks follow it.
     *
     * [washA] and [washB] are opaque, the wash at its strongest, at the window's edge; [mark] is
     * translucent, drawn over whatever is under it, so its strongest is over a wash.
     */
    data class QuestionMarks(
        val page: Color,
        val washA: Color,
        val washB: Color,
        val mark: Color,
    ) : ThemeArt {
        override val colors: List<Color> =
            listOf(washA, washB, mark.compositeOver(washA), mark.compositeOver(washB), mark.compositeOver(page))

        override fun DrawScope.draw() {
            val wide = size.width > size.height && size.width >= WyrDefaultDimens.wideLayoutMinWidth.toPx()
            val stops =
                arrayOf(0f to washA, WASH_CLEAR_FROM to page, WASH_CLEAR_TO to page, 1f to washB)
            val wash =
                if (wide) {
                    Brush.horizontalGradient(colorStops = stops, startX = 0f, endX = size.width)
                } else {
                    Brush.verticalGradient(colorStops = stops, startY = 0f, endY = size.height)
                }
            drawRect(wash)

            val markSize = WyrDefaultDimens.backgroundMarkSize.toPx()
            QUESTION_MARKS.forEach { place ->
                // On a wide window the fractions swap, so the marks follow the wash.
                val (x, y) = if (wide) place.y to place.x else place.x to place.y
                translate(x * size.width, y * size.height) {
                    rotate(place.degrees, pivot = Offset.Zero) {
                        scale(place.scale * markSize / MARK_GRID, pivot = Offset.Zero) {
                            translate(-MARK_GRID / 2, -MARK_GRID / 2) { questionMark(mark) }
                        }
                    }
                }
            }
        }

        /**
         * A question mark on a grid of [MARK_GRID] each way, as an icon is drawn: the hook, one stroke
         * with round ends, from its left end over the top and down into the stem, and the dot under it.
         */
        private fun DrawScope.questionMark(color: Color) {
            val hook =
                Path().apply {
                    moveTo(8.5f, 9f)
                    cubicTo(8.5f, 6.8f, 10.1f, 5f, 12f, 5f)
                    cubicTo(13.9f, 5f, 15.5f, 6.5f, 15.5f, 8.5f)
                    cubicTo(15.5f, 10.8f, 12f, 11.4f, 12f, 14.5f)
                }
            drawPath(hook, color, style = Stroke(width = MARK_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color, radius = MARK_DOT, center = Offset(MARK_GRID / 2, MARK_DOT_AT))
        }
    }

    /** A grid running to the horizon, a half sun on it, and stars above. */
    data class NeonGrid(
        val grid: Color,
        val glow: Color,
    ) : ThemeArt {
        override val colors: List<Color> = listOf(grid, glow)

        override fun DrawScope.draw() {
            val horizon = size.height * HORIZON
            val middle = size.width / 2
            val line = LINE_WIDTH.dp.toPx()

            stars(count = STARS, top = 0f, bottom = horizon * STAR_ZONE, color = glow, seed = NEON_SEED)
            // The sun, its lower half behind the horizon.
            val sun = size.minDimension * SUN_RADIUS
            drawArc(
                color = glow,
                startAngle = HALF_TURN,
                sweepAngle = HALF_TURN,
                useCenter = true,
                topLeft = Offset(middle - sun, horizon - sun),
                size = Size(sun * 2, sun * 2),
            )
            drawLine(grid, Offset(0f, horizon), Offset(size.width, horizon), strokeWidth = line)
            // The lines running away, from the bottom edge to one point in the horizon's middle.
            for (i in -GRID_RAYS..GRID_RAYS) {
                val bottom = middle + i * size.width * RAY_SPREAD
                drawLine(grid, Offset(middle + i * line, horizon), Offset(bottom, size.height), strokeWidth = line)
            }
            // The lines across, closer together toward the horizon.
            for (k in 1..GRID_ROWS) {
                val y = horizon + (k.toFloat() / GRID_ROWS).pow(2) * (size.height - horizon)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = line)
            }
        }
    }

    /** Two waves along the bottom, the nearer lower, and bubbles rising on the right. */
    data class Waves(
        val far: Color,
        val near: Color,
        val bubbles: Color,
    ) : ThemeArt {
        override val colors: List<Color> = listOf(far, near, bubbles)

        override fun DrawScope.draw() {
            wave(level = FAR_WAVE, height = WAVE_HEIGHT, length = FAR_WAVE_LENGTH, shift = 0f, color = far)
            wave(level = NEAR_WAVE, height = WAVE_HEIGHT, length = NEAR_WAVE_LENGTH, shift = WAVE_SHIFT, color = near)
            val sequence = Sequence(OCEAN_SEED)
            repeat(BUBBLES) {
                val x = size.width * (BUBBLE_LEFT + sequence.next() * (1 - BUBBLE_LEFT))
                val y = size.height * (BUBBLE_TOP + sequence.next() * (FAR_WAVE - BUBBLE_TOP))
                val radius = (BUBBLE_MIN + sequence.next() * (BUBBLE_MAX - BUBBLE_MIN)).dp.toPx()
                drawCircle(bubbles, radius, Offset(x, y), style = Stroke(width = LINE_WIDTH.dp.toPx()))
            }
        }

        /** A wave whose crests stand at [level] of the height, [height] of it high, filled to the bottom. */
        private fun DrawScope.wave(
            level: Float,
            height: Float,
            length: Float,
            shift: Float,
            color: Color,
        ) {
            val base = size.height * level
            val amplitude = size.height * height
            val wavelength = size.width * length
            val path = Path()
            path.moveTo(0f, size.height)
            var x = 0f
            val step = size.width / WAVE_STEPS
            while (x <= size.width + step) {
                path.lineTo(x, base + amplitude * sin(2 * PI.toFloat() * x / wavelength + shift))
                x += step
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, color)
        }
    }

    /** A line of pines behind two rolling hills along the bottom. */
    data class Forest(
        val far: Color,
        val near: Color,
        val trees: Color,
    ) : ThemeArt {
        override val colors: List<Color> = listOf(far, near, trees)

        override fun DrawScope.draw() {
            // The pines first, standing on the far hill's ridge, their feet just behind it, which is
            // drawn over them.
            val sequence = Sequence(FOREST_SEED)
            var x = 0f
            while (x < size.width) {
                val height = size.height * (PINE_MIN + sequence.next() * (PINE_MAX - PINE_MIN))
                val foot = size.height * (farRidgeAt(x / size.width) + PINE_SINK + sequence.next() * PINE_SINK)
                pine(x, foot, height, trees)
                x += height * (PINE_GAP_MIN + sequence.next() * (PINE_GAP_MAX - PINE_GAP_MIN))
            }
            hill(
                start = FAR_HILL_START,
                peak = FAR_HILL_PEAK,
                peakAt = FAR_HILL_PEAK_AT,
                end = FAR_HILL_END,
                color = far,
            )
            hill(
                start = NEAR_HILL_START,
                peak = NEAR_HILL_PEAK,
                peakAt = NEAR_HILL_PEAK_AT,
                end = NEAR_HILL_END,
                color = near,
            )
        }

        /**
         * How far down the far hill's ridge is, in the height, at [across] of the width: the two curves
         * [hill] draws it with, each of whose points moves evenly across, so the one at [across] is found
         * directly.
         */
        private fun farRidgeAt(across: Float): Float =
            if (across <= FAR_HILL_PEAK_AT) {
                val t = across / FAR_HILL_PEAK_AT
                val before = (1 - t) * (1 - t)
                before * FAR_HILL_START + (1 - before) * FAR_HILL_PEAK
            } else {
                val t = (across - FAR_HILL_PEAK_AT) / (1 - FAR_HILL_PEAK_AT)
                (1 - t * t) * FAR_HILL_PEAK + t * t * FAR_HILL_END
            }

        /** A pine of [height], centred on [x], standing at [foot]: three tiers, each wider than the one above. */
        private fun DrawScope.pine(
            x: Float,
            foot: Float,
            height: Float,
            color: Color,
        ) {
            val half = height * PINE_WIDTH / 2
            val path = Path()
            repeat(PINE_TIERS) { tier ->
                val top = foot - height + tier * height * PINE_TIER_STEP
                val bottom = top + height * PINE_TIER_HEIGHT
                val reach = half * (PINE_TOP_REACH + tier * (1 - PINE_TOP_REACH) / (PINE_TIERS - 1))
                path.moveTo(x, top)
                path.lineTo(x + reach, bottom)
                path.lineTo(x - reach, bottom)
                path.close()
            }
            drawPath(path, color)
            drawRect(
                color,
                topLeft = Offset(x - half * PINE_TRUNK, foot - height * PINE_TRUNK_HEIGHT),
                size = Size(half * PINE_TRUNK * 2, height * PINE_TRUNK_HEIGHT),
            )
        }

        /** A hill from [start] at the left edge over [peak] at [peakAt] across to [end] at the right. */
        private fun DrawScope.hill(
            start: Float,
            peak: Float,
            peakAt: Float,
            end: Float,
            color: Color,
        ) {
            val path = Path()
            path.moveTo(0f, size.height)
            path.lineTo(0f, size.height * start)
            path.quadraticTo(size.width * peakAt / 2, size.height * peak, size.width * peakAt, size.height * peak)
            path.quadraticTo(
                size.width * (1 + peakAt) / 2,
                size.height * peak,
                size.width,
                size.height * end,
            )
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, color)
        }
    }

    /** A low sun behind two dunes along the bottom. */
    data class Dunes(
        val sun: Color,
        val far: Color,
        val near: Color,
    ) : ThemeArt {
        override val colors: List<Color> = listOf(sun, far, near)

        override fun DrawScope.draw() {
            drawCircle(
                sun,
                radius = size.minDimension * SUN_RADIUS,
                center = Offset(size.width * DUNE_SUN_AT, size.height * DUNE_SUN_LEVEL),
            )
            dune(
                left = FAR_DUNE_LEFT,
                crest = FAR_DUNE_CREST,
                crestAt = FAR_DUNE_AT,
                right = FAR_DUNE_RIGHT,
                color = far,
            )
            dune(
                left = NEAR_DUNE_LEFT,
                crest = NEAR_DUNE_CREST,
                crestAt = NEAR_DUNE_AT,
                right = NEAR_DUNE_RIGHT,
                color = near,
            )
        }

        /** A dune from [left] at the left edge up to its [crest] at [crestAt] and down to [right]. */
        private fun DrawScope.dune(
            left: Float,
            crest: Float,
            crestAt: Float,
            right: Float,
            color: Color,
        ) {
            val path = Path()
            path.moveTo(0f, size.height)
            path.lineTo(0f, size.height * left)
            path.cubicTo(
                size.width * crestAt * 0.5f,
                size.height * left,
                size.width * crestAt * 0.7f,
                size.height * crest,
                size.width * crestAt,
                size.height * crest,
            )
            path.cubicTo(
                size.width * (crestAt + (1 - crestAt) * 0.3f),
                size.height * crest,
                size.width * (crestAt + (1 - crestAt) * 0.6f),
                size.height * right,
                size.width,
                size.height * right,
            )
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, color)
        }
    }
}

/** Draws [art] over the whole of this scope. */
fun DrawScope.drawThemeArt(art: ThemeArt) {
    with(art) { draw() }
}

/** [count] small stars between [top] and [bottom], where a sequence from [seed] puts them. */
private fun DrawScope.stars(
    count: Int,
    top: Float,
    bottom: Float,
    color: Color,
    seed: Int,
) {
    val sequence = Sequence(seed)
    repeat(count) {
        val centre = Offset(size.width * sequence.next(), top + (bottom - top) * sequence.next())
        val radius = (STAR_MIN + sequence.next() * (STAR_MAX - STAR_MIN)).dp.toPx()
        drawCircle(color, radius, centre)
    }
}

/**
 * Numbers from 0 to 1 that look scattered but come out the same for the same [seed], every time: a
 * linear congruential generator, so the art is the same in every frame, on every device and in a test.
 */
private class Sequence(
    seed: Int,
) {
    private var state: Long = seed.toLong()

    fun next(): Float {
        state = (state * LCG_MULTIPLIER + LCG_INCREMENT) and LCG_MASK
        return (state shr LCG_SHIFT).toFloat() / (1L shl LCG_BITS).toFloat()
    }
}

/**
 * Where one of the game's own question marks stands, as fractions of the window across ([x]) and down
 * ([y]) held upright, turned by [degrees], at [scale] of [WyrDimens.backgroundMarkSize].
 */
private data class Place(
    val x: Float,
    val y: Float,
    val scale: Float,
    val degrees: Float,
)

/** The game's own question marks, a few, by the edges more than in the middle, where the cards are. */
private val QUESTION_MARKS =
    listOf(
        Place(x = 0.1f, y = 0.14f, scale = 1f, degrees = -14f),
        Place(x = 0.88f, y = 0.2f, scale = 0.7f, degrees = 12f),
        Place(x = 0.07f, y = 0.47f, scale = 0.55f, degrees = 8f),
        Place(x = 0.9f, y = 0.6f, scale = 1.1f, degrees = -10f),
        Place(x = 0.16f, y = 0.86f, scale = 0.8f, degrees = 16f),
        Place(x = 0.84f, y = 0.94f, scale = 0.6f, degrees = -6f),
    )

/** Where the wash of card A's colour has faded out, and where card B's starts. */
private const val WASH_CLEAR_FROM = 0.42f
private const val WASH_CLEAR_TO = 0.58f

/** A question mark's grid, its stroke's width, its dot's radius and how far down the dot is, as an icon's. */
private const val MARK_GRID = 24f
private const val MARK_STROKE = 2f
private const val MARK_DOT = 2f
private const val MARK_DOT_AT = 19f

private const val LINE_WIDTH = 1.5f
private const val HALF_TURN = 180f

private const val HORIZON = 0.7f
private const val STAR_ZONE = 0.9f
private const val STARS = 36
private const val STAR_MIN = 1f
private const val STAR_MAX = 2.2f
private const val SUN_RADIUS = 0.22f
private const val GRID_RAYS = 9
private const val RAY_SPREAD = 0.18f
private const val GRID_ROWS = 6
private const val NEON_SEED = 7

private const val FAR_WAVE = 0.74f
private const val NEAR_WAVE = 0.84f
private const val WAVE_HEIGHT = 0.02f
private const val FAR_WAVE_LENGTH = 0.9f
private const val NEAR_WAVE_LENGTH = 0.6f
private const val WAVE_SHIFT = 1.3f
private const val WAVE_STEPS = 48
private const val BUBBLES = 9
private const val BUBBLE_LEFT = 0.62f
private const val BUBBLE_TOP = 0.2f
private const val BUBBLE_MIN = 4f
private const val BUBBLE_MAX = 11f
private const val OCEAN_SEED = 11

private const val FAR_HILL_START = 0.78f
private const val FAR_HILL_PEAK = 0.68f
private const val FAR_HILL_PEAK_AT = 0.7f
private const val FAR_HILL_END = 0.74f
private const val NEAR_HILL_START = 0.82f
private const val NEAR_HILL_PEAK = 0.8f
private const val NEAR_HILL_PEAK_AT = 0.25f
private const val NEAR_HILL_END = 0.9f
private const val PINE_MIN = 0.05f
private const val PINE_MAX = 0.09f
private const val PINE_SINK = 0.012f
private const val PINE_GAP_MIN = 0.3f
private const val PINE_GAP_MAX = 0.75f
private const val PINE_WIDTH = 0.6f
private const val PINE_TIERS = 3
private const val PINE_TIER_STEP = 0.26f
private const val PINE_TIER_HEIGHT = 0.46f
private const val PINE_TOP_REACH = 0.55f
private const val PINE_TRUNK = 0.14f
private const val PINE_TRUNK_HEIGHT = 0.2f
private const val FOREST_SEED = 23

private const val DUNE_SUN_AT = 0.68f
private const val DUNE_SUN_LEVEL = 0.76f
private const val FAR_DUNE_LEFT = 0.8f
private const val FAR_DUNE_CREST = 0.72f
private const val FAR_DUNE_AT = 0.35f
private const val FAR_DUNE_RIGHT = 0.82f
private const val NEAR_DUNE_LEFT = 0.9f
private const val NEAR_DUNE_CREST = 0.8f
private const val NEAR_DUNE_AT = 0.75f
private const val NEAR_DUNE_RIGHT = 0.86f

private const val LCG_MULTIPLIER = 25_214_903_917L
private const val LCG_INCREMENT = 11L
private const val LCG_MASK = (1L shl 48) - 1
private const val LCG_SHIFT = 24
private const val LCG_BITS = 24

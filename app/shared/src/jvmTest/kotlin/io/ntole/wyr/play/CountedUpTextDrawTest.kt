package io.ntole.wyr.play

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import io.ntole.wyr.CountedBy
import io.ntole.wyr.Recompositions
import io.ntole.wyr.everyText
import io.ntole.wyr.renderAt
import io.ntole.wyr.theme.WyrLightColors
import io.ntole.wyr.theme.WyrTheme
import io.ntole.wyr.theme.WyrTypeScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reveal's count up on its own (CLAUDE.md §8d, *The Play screen*), drawn off screen frame by
 * frame at 60 a second on the scene's clock: which numbers it draws and when, and what a frame of it
 * costs. How it looks on the cards is `PlayScreenDrawTest`'s.
 */
class CountedUpTextDrawTest {
    /**
     * The count is drawn from 0 at the start, a number between halfway, its target at 2.5 seconds, and
     * nothing again once it is there: the timing the reveal had when the count was a text.
     */
    @Test
    fun `the count is drawn from 0 up to its target in two and a half seconds`() {
        val drawn = drawnFrameByFrame(target = 70)
        assertEquals(0, drawn.getValue(0), "at the start")
        assertTrue(drawn.getValue(COUNTED_UP / 2) in 1 until 70, "halfway: $drawn")
        assertEquals(70, drawn.getValue(COUNTED_UP), "at 2.5 seconds")
        assertEquals(70, drawn.getValue(COUNTED_UP * 2), "after")
    }

    /**
     * Each whole number the count reaches is drawn once, in order: a frame that leaves it on the
     * number it was on draws nothing again. A small target reaches every number on the way; a large
     * one, faster than a number a frame at first, draws no more numbers than it counts through.
     */
    @Test
    fun `each number the count reaches is drawn once in order`() {
        listOf(3, 70).forEach { target ->
            val worded = mutableListOf<Int>()
            drawnFrameByFrame(target, worded)
            // First the target's own text, laid out once as the scene is first composed.
            assertEquals(target, worded.first(), "the text laid out for $target")
            val drawn = worded.drop(1)
            assertEquals(drawn.distinct().sorted(), drawn, "the numbers drawn for $target")
            assertEquals(listOf(0, target), listOf(drawn.first(), drawn.last()), "the numbers drawn for $target")
            if (target == 3) assertEquals(listOf(0, 1, 2, 3), drawn)
            assertTrue(drawn.size <= target + 1, "$target drawn ${drawn.size} times in ${FRAMES.size} frames")
        }
    }

    /** What a screen reader reads is the target's own text, from the first frame, never the count. */
    @Test
    fun `a screen reader reads the target from the first frame`() {
        withCount(70) { scene ->
            listOf(0L, COUNTED_UP / 2, COUNTED_UP).forEach { time ->
                scene.renderAt(time)
                assertEquals(listOf("70%"), scene.everyText(), "at ${time / 1_000_000} ms")
            }
        }
    }

    /**
     * A frame of the count up is only drawn: however many frames it takes, nothing is composed again
     * and the text is neither measured nor placed anew, so nothing around it is either.
     */
    @Test
    fun `a frame of the count up is neither composed nor laid out`() {
        val recompositions = Recompositions()
        var measured = 0
        var placed = 0
        val counting =
            Modifier.layout { measurable, constraints ->
                measured++
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placed++
                    placeable.place(0, 0)
                }
            }
        withCount(70, modifier = counting, recompositions = recompositions) { scene ->
            scene.renderAt(FRAME)
            val before = Triple(recompositions.scopesEntered, measured, placed)
            FRAMES.drop(2).forEach { time ->
                scene.renderAt(time)
                assertEquals(
                    before,
                    Triple(recompositions.scopesEntered, measured, placed),
                    "composed, measured and placed by ${time / 1_000_000} ms",
                )
            }
        }
    }

    /**
     * The number drawn last by each of [FRAMES] and by one long after, of a count up to [target],
     * with every number worded, laid out or drawn, added to [worded] in turn.
     */
    private fun drawnFrameByFrame(
        target: Int,
        worded: MutableList<Int> = mutableListOf(),
    ): Map<Long, Int> {
        val drawn = mutableMapOf<Long, Int>()
        withCount(target, text = { number -> "$number%".also { worded += number } }) { scene ->
            (FRAMES + COUNTED_UP * 2).forEach { time ->
                scene.renderAt(time)
                drawn[time] = worded.last()
            }
        }
        return drawn
    }

    /**
     * [test] on a count up to [target], worded by [text], in the middle of a scene of the light theme,
     * with [modifier], its composition counted by [recompositions], drawn at time 0.
     */
    private fun withCount(
        target: Int,
        text: (Int) -> String = { "$it%" },
        modifier: Modifier = Modifier,
        recompositions: Recompositions = Recompositions(),
        test: (ImageComposeScene) -> Unit,
    ) {
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                CountedBy(recompositions) {
                    WyrTheme(darkTheme = false) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CountedUpText(target = target, text = text, style = STYLE, modifier = modifier)
                        }
                    }
                }
            }
        try {
            scene.renderAt(0)
            test(scene)
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 200
        const val HEIGHT = 100

        /** The scene's clock, in nanoseconds, once the count has reached its target. */
        const val COUNTED_UP = COUNT_UP_MILLIS * 1_000_000L

        /** One frame of a phone drawing 60 a second, in nanoseconds. */
        const val FRAME = 1_000_000_000L / 60

        /**
         * Every frame of the count up at 60 a second, from the first, at 0, to the one at 2.5 seconds,
         * and one halfway.
         */
        val FRAMES: List<Long> = ((0..COUNTED_UP / FRAME).map { it * FRAME } + COUNTED_UP / 2 + COUNTED_UP).sorted()

        /** The cards' percentage in card A's colours. */
        val STYLE =
            TextStyle(
                color = WyrLightColors.onOptionA,
                fontSize = WyrTypeScale.percentage,
                fontWeight = FontWeight.ExtraBold,
            )
    }
}

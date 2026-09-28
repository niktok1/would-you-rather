package io.ntole.wyr.play

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
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
     * The count is drawn from 0 at the start, a number between halfway, its target at 3 seconds, and
     * nothing again once it is there: the timing the reveal had when the count was a text.
     */
    @Test
    fun `the count is drawn from 0 up to its target in three seconds`() {
        val drawn = drawnFrameByFrame(target = 70)
        assertEquals(0, drawn.getValue(0), "at the start")
        assertTrue(drawn.getValue(COUNTED_UP / 2) in 1 until 70, "halfway: $drawn")
        assertEquals(70, drawn.getValue(COUNTED_UP), "at 3 seconds")
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
        // Kept in saved state or not: keeping it writes a plain field on each frame, which composes nothing.
        listOf(null, SAVE_KEY).forEach { saveKey -> assertOnlyDrawn(saveKey) }
    }

    private fun assertOnlyDrawn(saveKey: String?) {
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
        withCount(
            70,
            modifier = counting,
            recompositions = recompositions,
            saveKey = saveKey,
            registry = SaveableStateRegistry(null) { true },
        ) { scene ->
            scene.renderAt(FRAME)
            val before = Triple(recompositions.scopesEntered, measured, placed)
            FRAMES.drop(2).forEach { time ->
                scene.renderAt(time)
                assertEquals(
                    before,
                    Triple(recompositions.scopesEntered, measured, placed),
                    "composed, measured and placed by ${time / 1_000_000} ms, kept as $saveKey",
                )
            }
            recompositions.assertCounting(scene, FRAMES.last())
        }
    }

    /**
     * A composition made anew mid-count, as an Android activity is on a rotation, its saved state
     * restored, goes on from the number the count had reached, never from 0, and reaches its target
     * in the time the count had left (CLAUDE.md §8d, *The Play screen*).
     */
    @Test
    fun `a count restored part way goes on from where it was`() {
        val first = mutableListOf<Int>()
        val saved = countedAndSaved(until = RESUMED_AT, worded = first)
        val before = first.last()
        assertTrue(before in 1 until 70, "part way when saved: $before")

        val again = mutableListOf<Int>()
        val drawn = mutableMapOf<Long, Int>()
        val left = COUNTED_UP - RESUMED_AT
        val text = { number: Int -> "$number%".also { again += number } }
        withCount(70, text = text, saveKey = SAVE_KEY, registry = registry(saved)) { scene ->
            drawn[0] = again.last()
            framesUntil(left + FRAME).forEach { time ->
                scene.renderAt(time)
                drawn[time] = again.last()
            }
        }
        assertEquals(before, drawn.getValue(0), "the first frame after the restore")
        val counted = again.drop(1)
        assertEquals(counted.distinct().sorted(), counted, "the numbers drawn after the restore")
        val frames = framesUntil(left + FRAME)
        assertTrue(drawn.getValue(frames[frames.size / 2]) in before + 1 until 70, "on the way: $drawn")
        assertEquals(70, drawn.getValue(frames.last()), "in the time it had left")
    }

    /**
     * A composition made anew once the count is done draws the target from its first frame and counts
     * nothing: every number drawn is the target.
     */
    @Test
    fun `a count restored once done draws its target at once`() {
        val saved = countedAndSaved(until = COUNTED_UP + FRAME)
        val again = mutableListOf<Int>()
        val text = { number: Int -> "$number%".also { again += number } }
        withCount(70, text = text, saveKey = SAVE_KEY, registry = registry(saved)) { scene ->
            FRAMES.forEach { time -> scene.renderAt(time) }
        }
        assertEquals(setOf(70), again.toSet(), "the numbers drawn after the restore")
    }

    /**
     * A count up to 70, kept under [SAVE_KEY], drawn a frame at a time until [until], every number worded
     * added to [worded]; what its composition saved, as an activity saves before it is destroyed.
     */
    private fun countedAndSaved(
        until: Long,
        worded: MutableList<Int> = mutableListOf(),
    ): Map<String, List<Any?>> {
        val registry = registry(null)
        var saved: Map<String, List<Any?>> = emptyMap()
        val text = { number: Int -> "$number%".also { worded += number } }
        withCount(70, text = text, saveKey = SAVE_KEY, registry = registry) { scene ->
            framesUntil(until).forEach { time -> scene.renderAt(time) }
            // Before the composition goes, as an activity saves its state before it is destroyed.
            saved = registry.performSave()
        }
        return saved
    }

    /** A registry of saved state, restoring [restored], taking anything. */
    private fun registry(restored: Map<String, List<Any?>>?) = SaveableStateRegistry(restored) { true }

    /** Every frame at 60 a second from the first after 0 to [until]. */
    private fun framesUntil(until: Long): List<Long> = (1..until / FRAME).map { it * FRAME }

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
        saveKey: String? = null,
        registry: SaveableStateRegistry? = null,
        test: (ImageComposeScene) -> Unit,
    ) {
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    CountedBy(recompositions) {
                        WyrTheme(darkTheme = false) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CountedUpText(
                                    counted = rememberCountUp(target, saveKey = saveKey),
                                    target = target,
                                    text = text,
                                    style = STYLE,
                                    modifier = modifier,
                                )
                            }
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

        /** The question a kept count is kept for. */
        const val SAVE_KEY = "q1"

        /** Where a restored count was, in nanoseconds: 40% of the way through. */
        const val RESUMED_AT = COUNTED_UP * 2 / 5

        /**
         * Every frame of the count up at 60 a second, from the first, at 0, to the one at 3 seconds,
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

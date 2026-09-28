package io.ntole.wyr.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.ntole.wyr.CountedBy
import io.ntole.wyr.MotionOff
import io.ntole.wyr.Recompositions
import io.ntole.wyr.nodes
import io.ntole.wyr.renderAt
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrDefaultDimens
import io.ntole.wyr.theme.WyrMotion
import io.ntole.wyr.theme.WyrTheme
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The screens' transitions (CLAUDE.md §5b, *Motion*), stepped frame by frame on the scene's clock at 60
 * a second: a screen opened comes in from the end, fading, and the one it covers goes the other way;
 * going back runs the other way round; each ends exactly where the screen stands with no motion, the
 * screen left gone; a frame of one composes nothing; and Play opened from Home, which the two screens
 * fade through by themselves, is cut to at once.
 */
class ScreenTransitionsDrawTest {
    @Test
    fun `a screen opened comes in from the right and the one it covers goes left`() {
        withScreens(opened = listOf(Screen.Play)) { scene, navigator, clock ->
            navigator.open(Screen.Categories)
            val start = clock.next(scene)
            assertEquals(listOf("categories", "play"), scene.texts().sorted(), "both, as it starts")
            assertEquals(SLIDE, scene.left("categories"), 1f, "the new screen starts a slide to the right")
            assertEquals(0f, scene.left("play"), 1f, "the old one starts where it stood")

            clock.until(scene, start + HALF)
            assertTrue(scene.left("categories") in 0f..SLIDE, "halfway: ${scene.left("categories")}")
            assertTrue(scene.left("play") in -SLIDE..0f, "halfway: ${scene.left("play")}")

            clock.until(scene, start + WHOLE + 2 * FRAME)
            assertEquals(listOf("categories"), scene.texts(), "the screen left is gone")
            assertEquals(restingBounds(Screen.Categories), scene.bounds("categories"), "where it stands with no motion")
        }
    }

    /** Home's Play buttons fade Home out and Play in by themselves, so the change of screen is a cut. */
    @Test
    fun `Play opened from Home is there at once, left to their fade through`() {
        withScreens { scene, navigator, clock ->
            navigator.open(Screen.Play)
            clock.next(scene)
            clock.next(scene)
            assertEquals(listOf("play"), scene.texts(), "Home gone at once")
            assertEquals(restingBounds(Screen.Play), scene.bounds("play"), "where it stands with no motion")
        }
    }

    /** The home icon from Play goes back to Home, so it slides as back does, from the left. */
    @Test
    fun `the home icon from Play goes back to Home as back does`() {
        withScreens(opened = listOf(Screen.Play)) { scene, navigator, clock ->
            navigator.open(Screen.Home)
            val start = clock.next(scene)
            assertEquals(-SLIDE, scene.left("home"), 1f, "home starts a slide to the left")
            clock.until(scene, start + WHOLE + 2 * FRAME)
            assertEquals(listOf("home"), scene.texts())
        }
    }

    @Test
    fun `going back comes in from the left and the screen left goes right`() {
        withScreens(opened = listOf(Screen.Account)) { scene, navigator, clock ->
            navigator.back()
            val start = clock.next(scene)
            assertEquals(-SLIDE, scene.left("home"), 1f, "home starts a slide to the left")

            clock.until(scene, start + HALF)
            assertTrue(scene.left("home") in -SLIDE..0f, "halfway: ${scene.left("home")}")
            assertTrue(scene.left("account") in 0f..SLIDE, "halfway: ${scene.left("account")}")

            clock.until(scene, start + WHOLE + 2 * FRAME)
            assertEquals(listOf("home"), scene.texts())
            assertEquals(restingBounds(Screen.Home), scene.bounds("home"))
        }
    }

    /** In a right-to-left language forward is to the left: a screen opened comes in from there. */
    @Test
    fun `in a right-to-left language a screen opened comes in from the left`() {
        withScreens(direction = LayoutDirection.Rtl) { scene, navigator, clock ->
            navigator.open(Screen.Account)
            val start = clock.next(scene)
            val first = scene.left("account")
            clock.until(scene, start + WHOLE + 2 * FRAME)
            assertEquals(-SLIDE, first - scene.left("account"), 1f, "from where it comes to rest")
        }
    }

    /** Only the transition's start and end compose anything: a frame between them is placed and drawn. */
    @Test
    fun `a frame of a transition composes nothing`() {
        val recompositions = Recompositions()
        withScreens(recompositions = recompositions) { scene, navigator, clock ->
            navigator.open(Screen.Account)
            val start = clock.next(scene)
            clock.until(scene, start + 2 * FRAME)
            val before = recompositions.scopesEntered
            while (clock.time < start + WHOLE - 2 * FRAME) {
                clock.next(scene)
                assertEquals(before, recompositions.scopesEntered, "composed by ${(clock.time - start) / MILLI} ms")
            }
            recompositions.assertCounting(scene, clock.time)
        }
    }

    /** With the platform's animations off, Android's *Remove animations*, the new screen is there at once. */
    @Test
    fun `with animations off a screen opened is there at once`() {
        withScreens(context = MotionOff) { scene, navigator, clock ->
            navigator.open(Screen.Account)
            clock.next(scene)
            clock.next(scene)
            assertEquals(listOf("account"), scene.texts())
            assertEquals(restingBounds(Screen.Account), scene.bounds("account"))
        }
    }

    /** The scene's clock, which only goes forward, a frame at a time. */
    private class Clock {
        var time = 0L
            private set

        fun next(scene: ImageComposeScene): Long {
            time += FRAME
            scene.renderAt(time)
            return time
        }

        fun until(
            scene: ImageComposeScene,
            end: Long,
        ) {
            while (time < end) next(scene)
        }
    }

    private fun ImageComposeScene.bounds(key: String): Rect = nodes().single { key in it.texts }.boundsInRoot

    private fun ImageComposeScene.left(key: String): Float = nodes().single { key in it.texts }.positionInRoot.x

    /** Where [screen]'s content stands with no motion at all. */
    private fun restingBounds(screen: Screen): Rect {
        var bounds = Rect.Zero
        withScreens(opened = listOf(screen).filter { it != Screen.Home }, context = MotionOff) { scene, _, _ ->
            bounds = scene.bounds(screen.key)
        }
        return bounds
    }

    /**
     * [test] on [ScreenTransitions] over a navigator with [opened] on it above Home, each screen its key
     * in the middle, drawn at time 0 in [direction], its composition counted by [recompositions].
     */
    private fun withScreens(
        opened: List<Screen> = emptyList(),
        direction: LayoutDirection = LayoutDirection.Ltr,
        recompositions: Recompositions = Recompositions(),
        context: CoroutineContext = Dispatchers.Unconfined,
        test: (ImageComposeScene, Navigator, Clock) -> Unit,
    ) {
        val navigator = Navigator().apply { opened.forEach(::open) }
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f), coroutineContext = context) {
                CountedBy(recompositions) {
                    WyrTheme(darkTheme = false) {
                        CompositionLocalProvider(LocalLayoutDirection provides direction) {
                            ScreenTransitions(navigator, modifier = Modifier.fillMaxSize()) { screen ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) { Text(screen.key) }
                            }
                        }
                    }
                }
            }
        try {
            scene.renderAt(0)
            test(scene, navigator, Clock())
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 375
        const val HEIGHT = 599
        const val MILLI = 1_000_000L
        const val FRAME = 1_000_000_000L / 60
        val WHOLE = WyrMotion.SCREEN_MILLIS * MILLI
        val HALF = WHOLE / 2

        /** One pixel a dp. */
        val SLIDE = WyrDefaultDimens.screenSlide.value
    }
}

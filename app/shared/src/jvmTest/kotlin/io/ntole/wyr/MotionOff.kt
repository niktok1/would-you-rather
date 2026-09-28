package io.ntole.wyr

import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * A scene's context with every animation's duration scaled to nothing, as Android's *Remove
 * animations* scales it (CLAUDE.md §5b, *Motion*): each ends on the first frame it runs. For a test
 * of what a screen does rather than how it moves, whose scene draws every frame at time 0, where an
 * animation left running would never end.
 */
internal val MotionOff: CoroutineContext =
    Dispatchers.Unconfined +
        object : MotionDurationScale {
            override val scaleFactor: Float = 0f
        }

package io.ntole.wyr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.setObserver
import androidx.compose.ui.ImageComposeScene
import kotlin.test.assertTrue

/**
 * How many times the composition it observes has run a composable's scope, as the runtime's own
 * observer tells it: a scope entered is one composed or recomposed. What a frame costs to compose,
 * where a frame that is only drawn again adds nothing.
 *
 * A count that stays the same shows that nothing was composed only while the observer is attached,
 * so a test that relies on one ends with [assertCounting].
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
internal class Recompositions : CompositionObserver {
    var scopesEntered: Int = 0
        private set

    /** Read by one scope of [CountedBy]'s own and by nothing else. */
    val sentinel = mutableIntStateOf(0)

    /**
     * Recomposes [CountedBy]'s own scope on [scene], drawn again at [nanoTime], and fails unless that
     * was counted: the proof that this observer is attached, and was all along, since [CountedBy]
     * attaches it once, as its first composition is applied, and keeps it until it leaves.
     */
    fun assertCounting(
        scene: ImageComposeScene,
        nanoTime: Long,
    ) {
        val before = scopesEntered
        sentinel.intValue++
        scene.renderAt(nanoTime)
        assertTrue(scopesEntered > before, "a scope recomposed was not counted: the observer is not attached")
    }

    override fun onScopeEnter(scope: RecomposeScope) {
        scopesEntered++
    }

    override fun onBeginComposition(composition: ObservableComposition) = Unit

    override fun onReadInScope(
        scope: RecomposeScope,
        value: Any,
    ) = Unit

    override fun onScopeExit(scope: RecomposeScope) = Unit

    override fun onEndComposition(composition: ObservableComposition) = Unit

    override fun onScopeInvalidated(
        scope: RecomposeScope,
        value: Any?,
    ) = Unit

    override fun onScopeDisposed(scope: RecomposeScope) = Unit
}

/**
 * [content], its composition counted by [recompositions] once its first composition is applied: that
 * first composition is not counted, every one after it is. Fails if the composition cannot be
 * observed, rather than counting nothing.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@Composable
internal fun CountedBy(
    recompositions: Recompositions,
    content: @Composable () -> Unit,
) {
    val composition = currentComposer.composition
    DisposableEffect(composition) {
        val observing = checkNotNull(composition.setObserver(recompositions)) { "the composition cannot be observed" }
        onDispose { observing.dispose() }
    }
    Sentinel(recompositions)
    content()
}

/** A scope that reads [recompositions]' sentinel and nothing else, so a change to it recomposes this one. */
@Composable
private fun Sentinel(recompositions: Recompositions) {
    recompositions.sentinel.intValue
}

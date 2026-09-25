package io.ntole.wyr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.setObserver

/**
 * How many times the composition it observes has run a composable's scope, as the runtime's own
 * observer tells it: a scope entered is one composed or recomposed. What a frame costs to compose,
 * where a frame that is only drawn again adds nothing.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
internal class Recompositions : CompositionObserver {
    var scopesEntered: Int = 0
        private set

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

/** [content], its composition counted by [recompositions] from the first composition on. */
@OptIn(ExperimentalComposeRuntimeApi::class)
@Composable
internal fun CountedBy(
    recompositions: Recompositions,
    content: @Composable () -> Unit,
) {
    val composition = currentComposer.composition
    DisposableEffect(composition) {
        val observing = composition.setObserver(recompositions)
        onDispose { observing?.dispose() }
    }
    content()
}

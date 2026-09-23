package io.ntole.wyr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ntole.wyr.play.PlayScreen
import io.ntole.wyr.play.PlayViewModel
import io.ntole.wyr.theme.WyrTheme
import org.koin.compose.viewmodel.koinViewModel

/**
 * The app's root composable, identical on every platform.
 *
 * Each platform entry point does nothing but call this — everything below here is shared, per the
 * entry-point rule in CLAUDE.md §3. [io.ntole.wyr.di.initKoin] must have run first; `startKoin`
 * publishes the Compose context, so no `KoinContext` wrapper is needed here.
 */
@Composable
fun App() {
    WyrTheme {
        val viewModel = koinViewModel<PlayViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        PlayScreen(
            state = state,
            onChoose = viewModel::choose,
            onNext = viewModel::next,
            onRetry = viewModel::retry,
        )
    }
}

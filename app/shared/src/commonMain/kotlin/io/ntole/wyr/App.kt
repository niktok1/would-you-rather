package io.ntole.wyr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ntole.wyr.dev.DevConsoleScreen
import io.ntole.wyr.dev.DevConsoleViewModel
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
 *
 * Two root screens behind a tab row. The dev console is the default while functionality comes
 * before polish (CLAUDE.md §8d); the play screen is kept as it is, frozen.
 */
@Composable
fun App() {
    WyrTheme {
        var screen by rememberSaveable { mutableStateOf(RootScreen.Console) }

        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            // The insets are applied once here, so the screens below find them already consumed.
            Column(modifier = Modifier.fillMaxSize().safeContentPadding()) {
                PrimaryTabRow(selectedTabIndex = screen.ordinal) {
                    RootScreen.entries.forEach { entry ->
                        Tab(
                            selected = entry == screen,
                            onClick = { screen = entry },
                            text = { Text(entry.label) },
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (screen) {
                        RootScreen.Console -> DevConsole()
                        RootScreen.Play -> Play()
                    }
                }
            }
        }
    }
}

private enum class RootScreen(
    val label: String,
) {
    Console("Console"),
    Play("Play"),
}

@Composable
private fun DevConsole() {
    val viewModel = koinViewModel<DevConsoleViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val httpExchanges by viewModel.httpExchanges.collectAsStateWithLifecycle()

    DevConsoleScreen(
        state = state,
        httpExchanges = httpExchanges,
        onEnsureSession = viewModel::ensureSession,
        onNewGuest = viewModel::newGuest,
        onNextQuestion = viewModel::nextQuestion,
        onResetQueue = viewModel::resetQueue,
        onVote = viewModel::vote,
        onSkip = viewModel::skip,
        onVoteById = viewModel::voteById,
        onRetryLastVote = viewModel::retryLastVote,
        onAnswerMany = viewModel::answerMany,
        onReadStats = viewModel::readStats,
    )
}

@Composable
private fun Play() {
    val viewModel = koinViewModel<PlayViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    PlayScreen(
        state = state,
        onChoose = viewModel::choose,
        onNext = viewModel::next,
        onRetry = viewModel::retry,
    )
}

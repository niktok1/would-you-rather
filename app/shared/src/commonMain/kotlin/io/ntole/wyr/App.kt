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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ntole.wyr.account.AccountScreen
import io.ntole.wyr.account.AccountViewModel
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.dev.DevConsoleScreen
import io.ntole.wyr.dev.DevConsoleViewModel
import io.ntole.wyr.dev.submission.SubmissionConsole
import io.ntole.wyr.play.PlayScreen
import io.ntole.wyr.play.PlayViewModel
import io.ntole.wyr.theme.WyrTheme
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The app's root composable, identical on every platform.
 *
 * Each platform entry point does nothing but call this — everything below here is shared, per the
 * entry-point rule in CLAUDE.md §3. [io.ntole.wyr.di.initKoin] must have run first; `startKoin`
 * publishes the Compose context, so no `KoinContext` wrapper is needed here.
 *
 * The root screens are the ones [rootScreensFor] gives the build's environment, behind a tab row.
 * The game's screens take over the console's features one by one (CLAUDE.md §8d, *Current focus*).
 */
@Composable
fun App() {
    WyrTheme {
        val screens = rootScreensFor(koinInject<WyrEnvironment>())
        var screen by rememberSaveable { mutableStateOf(screens.first()) }

        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            // The insets are applied once here, so the screens below find them already consumed.
            Column(modifier = Modifier.fillMaxSize().safeContentPadding()) {
                PrimaryTabRow(selectedTabIndex = screens.indexOf(screen)) {
                    screens.forEach { entry ->
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
                        RootScreen.Account -> Account()
                    }
                }
            }
        }
    }
}

/**
 * The root screens a build for [environment] shows, the first of them opening. The game's screens,
 * Play and Account, are in every build. The dev console comes first while functionality comes before
 * polish (CLAUDE.md §8d), and only where the environment shows developer tools: a production build
 * opens on Play, and has no tab to reach the console.
 */
internal fun rootScreensFor(environment: WyrEnvironment): List<RootScreen> =
    if (environment.showsDeveloperTools) {
        listOf(RootScreen.Console, RootScreen.Play, RootScreen.Account)
    } else {
        listOf(RootScreen.Play, RootScreen.Account)
    }

internal enum class RootScreen(
    val label: String,
) {
    Console("Console"),
    Play("Play"),
    Account("Account"),
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
        onVoteById = viewModel::voteById,
        onRetryLastVote = viewModel::retryLastVote,
        onAnswerMany = viewModel::answerMany,
        onReadStats = viewModel::readStats,
        onToggleCategory = viewModel::toggleCategory,
        onSelectAllCategories = viewModel::selectAllCategories,
        submitSection = { SubmissionConsole() },
    )
}

@Composable
private fun Account() {
    val viewModel = koinViewModel<AccountViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Every time the tab is shown: the points move on the Play tab meanwhile, and a guest's are what
    // a login would leave behind.
    LaunchedEffect(viewModel) { viewModel.refresh() }

    AccountScreen(state = state, actions = viewModel)
}

@Composable
private fun Play() {
    val viewModel = koinViewModel<PlayViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    PlayScreen(
        state = state,
        onChoose = viewModel::choose,
        onSkip = viewModel::skip,
        onToggleLike = viewModel::toggleLike,
        onNext = viewModel::next,
        onRetry = viewModel::retry,
    )
}

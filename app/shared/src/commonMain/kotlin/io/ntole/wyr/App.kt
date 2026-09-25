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
import io.ntole.wyr.play.PlayScreen
import io.ntole.wyr.play.PlayViewModel
import io.ntole.wyr.submit.SubmitScreen
import io.ntole.wyr.submit.SubmitViewModel
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
 * The root screens are the game's, every [RootScreen] behind a tab row, in every build whatever
 * server it talks to (CLAUDE.md §8d, *Current focus*), opening on the first, Play.
 */
@Composable
fun App() {
    WyrTheme {
        val screens = RootScreen.entries
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
                        RootScreen.Play -> Play()
                        RootScreen.Submit -> Submit()
                        RootScreen.Account -> Account()
                    }
                }
            }
        }
    }
}

/** The game's root screens, as its tabs, in order: the first is the one the app opens on. */
internal enum class RootScreen(
    val label: String,
) {
    Play("Play"),
    Submit("Submit"),
    Account("Account"),
}

@Composable
private fun Account() {
    val viewModel = koinViewModel<AccountViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Every time the tab is shown: the points move on the Play tab meanwhile, and a guest's are what
    // a login would leave behind.
    LaunchedEffect(viewModel) { viewModel.refresh() }

    AccountScreen(state = state, actions = viewModel, environment = koinInject())
}

@Composable
private fun Submit() {
    val viewModel = koinViewModel<SubmitViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Every time the tab is shown: a moderator decides the player's submissions meanwhile.
    LaunchedEffect(viewModel) { viewModel.refresh() }

    SubmitScreen(state = state, actions = viewModel)
}

@Composable
private fun Play() {
    val viewModel = koinViewModel<PlayViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val picking by viewModel.picking.collectAsStateWithLifecycle()

    PlayScreen(
        state = state,
        categories = categories,
        picking = picking,
        onChoose = viewModel::choose,
        onSkip = viewModel::skip,
        onToggleLike = viewModel::toggleLike,
        onNext = viewModel::next,
        onRetry = viewModel::retry,
        onOpenCategories = viewModel::openCategories,
        onToggleCategory = viewModel::toggleCategory,
        onSelectAllCategories = viewModel::selectAllCategories,
        onApplyCategories = viewModel::applyCategories,
        onCloseCategories = viewModel::closeCategories,
    )
}

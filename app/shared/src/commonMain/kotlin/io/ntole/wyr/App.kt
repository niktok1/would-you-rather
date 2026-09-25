package io.ntole.wyr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ntole.wyr.account.AccountScreen
import io.ntole.wyr.account.AccountViewModel
import io.ntole.wyr.categories.CategoriesScreen
import io.ntole.wyr.categories.CategoriesViewModel
import io.ntole.wyr.home.HomeScreen
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LanguageViewModel
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.navigation.AccountTopBar
import io.ntole.wyr.navigation.BackTopBar
import io.ntole.wyr.navigation.Navigator
import io.ntole.wyr.navigation.PlayTopBar
import io.ntole.wyr.navigation.Screen
import io.ntole.wyr.navigation.SubmitTopBar
import io.ntole.wyr.navigation.SystemBack
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
 * The screens are the game's, in every build whatever server it talks to (CLAUDE.md §8d,
 * *Navigation*): Home first, and the rest opened from it through a [Navigator], a back stack made by
 * hand, no tabs and no navigation library. They are shown in the language picked on the Account
 * screen, Serbian Cyrillic until one is (§8f).
 */
@Composable
fun App() {
    val languages = koinViewModel<LanguageViewModel>()
    val language by languages.language.collectAsStateWithLifecycle()

    WyrTheme {
        WyrStrings(language) { Screens(language, onSelectLanguage = languages::select) }
    }
}

/**
 * The screen on top of the back stack, and only it. Each screen's ViewModel belongs to the platform's
 * own owner, the activity's or the window's, as it did under the tabs, never to the back stack: a
 * screen left and come back to, Play above all, shows what it showed, its question included.
 */
@Composable
private fun Screens(
    language: Language,
    onSelectLanguage: (Language) -> Unit,
) {
    val navigator = rememberSaveable(saver = Navigator.Saver) { Navigator() }
    SystemBack(enabled = navigator.canGoBack, onBack = { navigator.back() })

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        // The insets are applied once here, so the screens below find them already consumed.
        Column(modifier = Modifier.fillMaxSize().safeContentPadding()) {
            when (navigator.current) {
                Screen.Home -> {
                    HomeScreen(
                        onPlay = { navigator.open(Screen.Play) },
                        onAccount = { navigator.open(Screen.Account) },
                    )
                }

                Screen.Play -> {
                    val picker = koinViewModel<CategoriesViewModel>()
                    PlayTopBar(
                        onHome = { navigator.open(Screen.Home) },
                        onAccount = { navigator.open(Screen.Account) },
                    )
                    Below {
                        Play(
                            onOpenCategories = {
                                // A visit of its own: what is played now ticked, and nothing searched.
                                picker.open()
                                navigator.open(Screen.Categories)
                            },
                        )
                    }
                }

                Screen.Account -> {
                    AccountTopBar(onBack = { navigator.back() }, onSubmit = { navigator.open(Screen.Submit) })
                    Below { Account(language, onSelectLanguage) }
                }

                Screen.Submit -> {
                    SubmitTopBar(onBack = { navigator.back() })
                    Below { Submit() }
                }

                Screen.Categories -> {
                    BackTopBar(onBack = { navigator.back() })
                    Below { Categories(language, onPlayed = { navigator.back() }) }
                }
            }
        }
    }
}

/** A screen under its top bar, in the height the bar leaves it. */
@Composable
private fun ColumnScope.Below(screen: @Composable () -> Unit) {
    Box(modifier = Modifier.weight(1f)) { screen() }
}

@Composable
private fun Account(
    language: Language,
    onSelectLanguage: (Language) -> Unit,
) {
    val viewModel = koinViewModel<AccountViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Every time the screen is shown: the points move on the Play screen meanwhile, and a guest's are
    // what a login would leave behind.
    LaunchedEffect(viewModel) { viewModel.refresh() }

    AccountScreen(
        state = state,
        actions = viewModel,
        environment = koinInject(),
        language = language,
        onSelectLanguage = onSelectLanguage,
    )
}

@Composable
private fun Submit() {
    val viewModel = koinViewModel<SubmitViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Every time the screen is shown: a moderator decides the player's submissions meanwhile.
    LaunchedEffect(viewModel) { viewModel.refresh() }

    SubmitScreen(state = state, actions = viewModel)
}

/**
 * The Categories screen, opened from Play, which starts the visit ([CategoriesViewModel.open]). Once
 * what is ticked is played, [onPlayed] goes back to Play, which shows a question from it; the back
 * arrow, or Android's back, leaves it with nothing played (CLAUDE.md §8d, *Categories*).
 */
@Composable
private fun Categories(
    language: Language,
    onPlayed: () -> Unit,
) {
    val viewModel = koinViewModel<CategoriesViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Every time the screen is shown: a moderator adds categories meanwhile.
    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(state.played) { if (state.played) onPlayed() }

    CategoriesScreen(state = state, language = language, actions = viewModel)
}

/** The Play screen; its categories, tapped, open the Categories screen ([onOpenCategories]). */
@Composable
private fun Play(onOpenCategories: () -> Unit) {
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
        onOpenCategories = onOpenCategories,
        onToggleCategory = viewModel::toggleCategory,
        onSelectAllCategories = viewModel::selectAllCategories,
        onApplyCategories = viewModel::applyCategories,
        onCloseCategories = viewModel::closeCategories,
    )
}

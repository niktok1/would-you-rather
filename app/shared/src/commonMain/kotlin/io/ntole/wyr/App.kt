package io.ntole.wyr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ntole.wyr.about.AboutScreen
import io.ntole.wyr.account.AccountScreen
import io.ntole.wyr.account.AccountViewModel
import io.ntole.wyr.account.AuthScreen
import io.ntole.wyr.analytics.LocalAnalytics
import io.ntole.wyr.analytics.ShownEffect
import io.ntole.wyr.analytics.UsageTracker
import io.ntole.wyr.analytics.rememberConfigurationChanging
import io.ntole.wyr.categories.CategoriesScreen
import io.ntole.wyr.categories.CategoriesViewModel
import io.ntole.wyr.core.domain.notice.DecisionNotices
import io.ntole.wyr.core.domain.session.CurrentSession
import io.ntole.wyr.core.domain.update.AppUpdate
import io.ntole.wyr.home.HomeScreen
import io.ntole.wyr.home.HomeViewModel
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LanguageViewModel
import io.ntole.wyr.language.LocalLanguage
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.navigation.AccountTopBar
import io.ntole.wyr.navigation.BackTopBar
import io.ntole.wyr.navigation.Navigator
import io.ntole.wyr.navigation.PlayTopBar
import io.ntole.wyr.navigation.Screen
import io.ntole.wyr.navigation.SystemBack
import io.ntole.wyr.play.CategoriesPlayed
import io.ntole.wyr.play.PlayScreen
import io.ntole.wyr.play.PlayViewModel
import io.ntole.wyr.play.QuestionMenu
import io.ntole.wyr.play.canChangeCategories
import io.ntole.wyr.play.canUseMenu
import io.ntole.wyr.play.categoriesPlayed
import io.ntole.wyr.services.AppServices
import io.ntole.wyr.submit.SubmitScreen
import io.ntole.wyr.submit.SubmitViewModel
import io.ntole.wyr.theme.WyrTheme
import io.ntole.wyr.update.UpdateScreen
import io.ntole.wyr.update.rememberUpdateButton
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
 * screen, Serbian Cyrillic until one is (§8f). The app's comings and goings and every screen shown
 * are reported to the analytics (§8g, [UsageTracker]). Once the server serves this build nothing more,
 * the one screen shown says a new version is available ([AppUpdate], §8e), whatever was shown before.
 */
@Composable
fun App() {
    val languages = koinViewModel<LanguageViewModel>()
    val language by languages.language.collectAsStateWithLifecycle()
    ReportForegroundAndBackground(koinInject(), language, services = koinInject())
    val updateRequired by koinInject<AppUpdate>().required.collectAsStateWithLifecycle()

    // Every tap on every screen is counted there (CLAUDE.md §8g, [io.ntole.wyr.analytics.tapped]).
    CompositionLocalProvider(LocalAnalytics provides koinInject()) {
        WyrTheme {
            WyrStrings(language) {
                if (updateRequired) {
                    UpdateRequired()
                } else {
                    Screens(language, onSelectLanguage = languages::select)
                }
            }
        }
    }
}

/** The update screen, where the screens were, inside the same insets. */
@Composable
private fun UpdateRequired() {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().safeContentPadding()) { UpdateScreen(button = rememberUpdateButton()) }
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
    val usage = koinInject<UsageTracker>()
    LaunchedEffect(navigator.current) { usage.show(navigator.current.key) }
    // A moderator decided a question of the player's, which they have not seen: a dot on the account icon.
    val notices = koinInject<DecisionNotices>()
    val unseen by notices.unseen.collectAsStateWithLifecycle()
    val news = unseen.isNotEmpty()
    // A tapped notification of a decision opens the Account screen, where My questions shows it.
    val services = koinInject<AppServices>()
    val accountAsked by services.accountAsked.collectAsStateWithLifecycle()
    LaunchedEffect(accountAsked) {
        if (accountAsked) {
            navigator.open(Screen.Account)
            services.accountShownForNotification()
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        // The insets are applied once here, so the screens below find them already consumed.
        Column(modifier = Modifier.fillMaxSize().safeContentPadding()) {
            when (navigator.current) {
                Screen.Home -> {
                    Home(
                        onPlay = { navigator.open(Screen.Play) },
                        onAccount = { navigator.open(Screen.Account) },
                        news = news,
                    )
                }

                Screen.Play -> {
                    val picker = koinViewModel<CategoriesViewModel>()
                    Play(
                        onHome = { navigator.open(Screen.Home) },
                        onAccount = { navigator.open(Screen.Account) },
                        news = news,
                        onOpenCategories = {
                            // A visit of its own: what is played now ticked, and nothing searched.
                            picker.open()
                            navigator.open(Screen.Categories)
                        },
                    )
                }

                Screen.Account -> {
                    AccountTopBar(onBack = { navigator.back() }, onAbout = { navigator.open(Screen.About) })
                    Below {
                        Account(
                            language = language,
                            onSelectLanguage = onSelectLanguage,
                            onOpenAuth = { navigator.open(Screen.Auth) },
                            onNewQuestion = { navigator.open(Screen.Submit) },
                            notices = notices,
                        )
                    }
                }

                Screen.Auth -> {
                    BackTopBar(onBack = { navigator.back() })
                    Below { Auth(onSignedIn = { navigator.back() }) }
                }

                Screen.Submit -> {
                    BackTopBar(onBack = { navigator.back() })
                    Below { Submit(onSent = { navigator.back() }) }
                }

                Screen.Categories -> {
                    BackTopBar(onBack = { navigator.back() })
                    Below { Categories(onPlayed = { navigator.back() }) }
                }

                Screen.About -> {
                    BackTopBar(onBack = { navigator.back() })
                    Below { About() }
                }
            }
        }
    }
}

/**
 * The About screen, with the account id of the session stored on the device, which it copies to send
 * by email for the account's deletion (CLAUDE.md §8d, *About*): read from the device and never from
 * the server, so it shows offline and mints no session. None while none is stored; and the new one
 * should the device become another player while it is shown.
 */
@Composable
private fun About() {
    val session = koinInject<CurrentSession>()
    val accountId by remember(session) { session.sessions }.collectAsStateWithLifecycle(session.current())

    AboutScreen(version = koinInject(), accountId = accountId)
}

/**
 * The Home screen, whose two Play buttons show how many picked each, read each time it is shown
 * (CLAUDE.md §8d, *Home picks*). A tap on either opens Play at once, [onPlay], and is counted in the
 * background, never holding the game up. The account icon has its dot while [news] waits there.
 */
@Composable
private fun Home(
    onPlay: () -> Unit,
    onAccount: () -> Unit,
    news: Boolean,
) {
    val viewModel = koinViewModel<HomeViewModel>()
    val picks by viewModel.picks.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.shown() }

    HomeScreen(
        picks = picks,
        onPlay = { side ->
            viewModel.pick(side)
            onPlay()
        },
        onAccount = onAccount,
        news = news,
    )
}

/**
 * The app coming to the foreground and going to the background, as the platform's lifecycle tells it,
 * to [usage]: Android's activity, the iOS view controller, the desktop window (minimized or not) and
 * the browser page (hidden or not). The app shown in [language]. [services] hear of each coming to the
 * foreground too, the launch's first, and start what runs by itself.
 */
@Composable
private fun ReportForegroundAndBackground(
    usage: UsageTracker,
    language: Language,
    services: AppServices,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val shownIn by rememberUpdatedState(language)
    val configurationChanging = rememberConfigurationChanging()
    DisposableEffect(lifecycle, usage, services) {
        fun cameToForeground() {
            usage.foreground(shownIn.tag)
            services.foreground()
        }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> cameToForeground()
                    Lifecycle.Event.ON_STOP -> usage.background(configurationChanging())
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
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
    onOpenAuth: () -> Unit,
    onNewQuestion: () -> Unit,
    notices: DecisionNotices,
) {
    val viewModel = koinViewModel<AccountViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The decisions the player had not seen, marked on their rows for this visit, a rotation's included,
    // and seen from the list's first read on, which takes the account icon's dot down (CLAUDE.md §8d,
    // *Submitting*). A visit's own, so the next starts with none. Only a list of the player playing counts.
    var newDecisions by rememberSaveable(stateSaver = DECISIONS_SAVER) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(state.submissions, state.readFor) {
        val read = state
        read.submissions?.let { listed -> newDecisions = newDecisions + notices.shown(listed, read.readFor) }
    }
    val submit = koinViewModel<SubmitViewModel>()
    val submitState by submit.state.collectAsStateWithLifecycle()

    // Every time the screen is shown: the points move on the Play screen meanwhile, a guest's are what
    // a login would leave behind, and a moderator decides the player's questions. A rotation's shows the
    // same visit, which the analytics count once (CLAUDE.md §8g).
    ShownEffect(viewModel, viewModel::shown)
    // A question sent from the form whose answer came once the player had come back here: My questions
    // was read before it was stored, so it is read again, once no read is in flight.
    LaunchedEffect(submitState.sent, state.isBusy) {
        if (submitState.sent && !state.isBusy) {
            submit.leftForm()
            viewModel.refresh()
        }
    }

    // The player's choice, kept on the device, which the analytics hold (CLAUDE.md §8g).
    val analytics = LocalAnalytics.current
    val statisticsOn by analytics.enabled.collectAsStateWithLifecycle()

    AccountScreen(
        state = state,
        actions = viewModel,
        environment = koinInject(),
        language = language,
        onSelectLanguage = onSelectLanguage,
        statisticsOn = statisticsOn,
        onStatisticsChange = analytics::setEnabled,
        onOpenAuth = onOpenAuth,
        onNewQuestion = onNewQuestion,
        newDecisions = newDecisions,
    )
}

/** A visit's new decisions, saved as a list, which every platform's saved state can hold. */
private val DECISIONS_SAVER: Saver<Set<String>, Any> =
    listSaver(save = { it.sorted() }, restore = { it.toSet() })

/**
 * The Auth page, on the Account screen's ViewModel: what it reads and what is typed are the Account
 * screen's. A register or a login that worked goes back to the Account screen, [onSignedIn], which
 * reads the player again as it is shown.
 */
@Composable
private fun Auth(onSignedIn: () -> Unit) {
    val viewModel = koinViewModel<AccountViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.authShown() }
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            viewModel.leftAuth()
            onSignedIn()
        }
    }

    AuthScreen(state = state, actions = viewModel)
}

/**
 * The Submit screen's form, opened from My questions. A question stored goes back to My questions,
 * [onSent], which reads the list again as the Account screen is shown; one stored once the player had
 * gone back is read again by the Account screen, if it is shown then.
 */
@Composable
private fun Submit(onSent: () -> Unit) {
    val viewModel = koinViewModel<SubmitViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Every time the form is shown: the points move meanwhile. Declared first, so it takes down a
    // `sent` left from a showing before, of a question whose answer came after the player went back.
    // A rotation's shows the same visit, which the analytics count once (CLAUDE.md §8g).
    ShownEffect(viewModel, viewModel::shown)
    LaunchedEffect(state.sent) {
        if (state.sent) {
            viewModel.leftForm()
            onSent()
        }
    }

    SubmitScreen(state = state, actions = viewModel)
}

/**
 * The Categories screen, opened from Play, which starts the visit ([CategoriesViewModel.open]). Once
 * what is ticked is played, [onPlayed] goes back to Play, which shows a question from it; the back
 * arrow, or Android's back, leaves it with nothing played (CLAUDE.md §8d, *Categories*).
 */
@Composable
private fun Categories(onPlayed: () -> Unit) {
    val viewModel = koinViewModel<CategoriesViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Every time the screen is shown: a moderator adds categories meanwhile.
    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(state.played) { if (state.played) onPlayed() }

    CategoriesScreen(state = state, actions = viewModel)
}

/**
 * The Play screen under its top bar, whose categories played, in its middle, open the Categories
 * screen ([onOpenCategories]) while the Play screen can take a change of them.
 */
@Composable
private fun ColumnScope.Play(
    onHome: () -> Unit,
    onAccount: () -> Unit,
    news: Boolean,
    onOpenCategories: () -> Unit,
) {
    val viewModel = koinViewModel<PlayViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val points by viewModel.points.collectAsStateWithLifecycle()

    // Every time the screen is shown: the points move on the Account and Submit screens meanwhile.
    LaunchedEffect(viewModel) { viewModel.refreshPoints() }
    // How long a question is on screen counts only while this screen is, the app in the foreground
    // (CLAUDE.md §8g): not while Account or the categories are shown over it, nor in the background.
    LifecycleStartEffect(viewModel) {
        viewModel.screenShown()
        onStopOrDispose { viewModel.screenHidden() }
    }

    PlayTopBar(
        onHome = onHome,
        onAccount = onAccount,
        news = news,
        // The menu about the question on screen: report it, or hide it or its author (CLAUDE.md §8d).
        menu = { QuestionMenu(enabled = state.canUseMenu, onPick = viewModel::pickFromMenu) },
    ) {
        CategoriesPlayed(
            text =
                categoriesPlayed(
                    categories,
                    all = LocalStrings.current.allCategories,
                    language = LocalLanguage.current,
                ),
            enabled = state.canChangeCategories,
            onClick = onOpenCategories,
        )
    }
    Below {
        PlayScreen(
            state = state,
            points = points,
            onChoose = viewModel::choose,
            onSkip = viewModel::skip,
            onReact = viewModel::react,
            onNext = viewModel::next,
            onRetry = viewModel::retry,
        )
    }
}

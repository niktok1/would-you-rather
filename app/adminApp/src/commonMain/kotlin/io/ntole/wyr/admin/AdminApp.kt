package io.ntole.wyr.admin

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ntole.wyr.admin.moderation.CategoriesScreen
import io.ntole.wyr.admin.moderation.ModerationActions
import io.ntole.wyr.admin.moderation.ModerationState
import io.ntole.wyr.admin.moderation.ModerationViewModel
import io.ntole.wyr.admin.moderation.PendingScreen
import io.ntole.wyr.admin.moderation.QuestionsScreen
import io.ntole.wyr.admin.moderation.Screen
import io.ntole.wyr.admin.moderation.TokenBar
import io.ntole.wyr.admin.moderation.isFull
import io.ntole.wyr.admin.moderation.retireWarningOf
import io.ntole.wyr.admin.theme.AdminDimens
import io.ntole.wyr.admin.theme.AdminTheme
import io.ntole.wyr.admin.theme.AdminType
import io.ntole.wyr.core.network.environment.WyrEnvironment
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The moderation app's root, the same on the desktop and in the browser. [io.ntole.wyr.admin.di.initAdminKoin]
 * must have run first.
 */
@Composable
fun AdminApp() {
    val viewModel = koinViewModel<ModerationViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Which tab is open is all this remembers across a recreation; the token is the ViewModel's.
    var screen by rememberSaveable { mutableStateOf(Screen.PENDING) }

    ModerationApp(
        environment = koinInject<WyrEnvironment>(),
        state = state,
        actions = viewModel,
        screen = screen,
        onScreenChange = { screen = it },
    )
}

/**
 * The server the app talks to, always on top, then the admin token, and [screen], one of the tabs.
 * Stateless, so what it shows is [state] alone, in the system's light or dark scheme unless
 * [darkTheme] says. The question waiting for Retire to be confirmed asks over it.
 */
@Composable
fun ModerationApp(
    environment: WyrEnvironment,
    state: ModerationState,
    actions: ModerationActions,
    screen: Screen,
    onScreenChange: (Screen) -> Unit,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    AdminTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize().safeContentPadding()) {
                Column(modifier = Modifier.widthIn(max = AdminDimens.contentMaxWidth).fillMaxSize()) {
                    EnvironmentBar(environment)
                    TokenBar(state, actions, modifier = Modifier.padding(AdminDimens.spaceMd))
                    PrimaryTabRow(selectedTabIndex = Screen.entries.indexOf(screen)) {
                        Screen.entries.forEach { entry ->
                            Tab(
                                selected = entry == screen,
                                onClick = { onScreenChange(entry) },
                                text = { Text(tabLabelOf(entry, state)) },
                            )
                        }
                    }
                    when (screen) {
                        Screen.PENDING -> PendingScreen(state, actions, modifier = Modifier.weight(1f))
                        Screen.QUESTIONS -> QuestionsScreen(state, actions, modifier = Modifier.weight(1f))
                        Screen.CATEGORIES -> CategoriesScreen(state, actions, modifier = Modifier.weight(1f))
                    }
                }
            }
            state.retiring?.let { questionId -> RetireDialog(questionId, state, actions) }
        }
    }
}

/** Retire asks first: it takes a question out of play for every player. */
@Composable
private fun RetireDialog(
    questionId: String,
    state: ModerationState,
    actions: ModerationActions,
) {
    val question = state.questions.questions?.firstOrNull { it.id == questionId }
    AlertDialog(
        onDismissRequest = actions::cancelRetire,
        title = { Text("Retire this question?") },
        text = { Text(retireWarningOf(question)) },
        confirmButton = { Button(onClick = actions::confirmRetire) { Text("Retire") } },
        dismissButton = { TextButton(onClick = actions::cancelRetire) { Text("Cancel") } },
    )
}

/**
 * A tab's name, with how many it lists once it has read them, and a `+` on a queue that may hold
 * more than one read lists.
 */
fun tabLabelOf(
    screen: Screen,
    state: ModerationState,
): String {
    val count =
        when (screen) {
            Screen.PENDING -> state.pending.submissions?.let { if (isFull(it)) "${it.size}+" else "${it.size}" }
            Screen.QUESTIONS -> state.questions.questions?.let { "${it.size}" }
            Screen.CATEGORIES -> state.categories.categories?.let { "${it.size}" }
        }
    return screen.label + count?.let { " ($it)" }.orEmpty()
}

/**
 * Which server every request goes to, never scrolled away. Production stands out in the error
 * colors: a decision there is made for every player.
 */
@Composable
private fun EnvironmentBar(environment: WyrEnvironment) {
    val production = environment == WyrEnvironment.PROD
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (production) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (production) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(AdminDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs),
        ) {
            Text(text = serverLineOf(environment), style = MaterialTheme.typography.titleMedium)
            Text(text = environment.apiBaseUrl, style = AdminType.code)
        }
    }
}

/** How the app names the server it moderates. */
fun serverLineOf(environment: WyrEnvironment): String = "Moderating the ${environment.displayName} server"

/** The desktop window's title, which names the server too, so no window can hide where it points. */
fun windowTitleOf(environment: WyrEnvironment): String =
    "WYR moderation · ${environment.displayName} · ${environment.apiBaseUrl}"

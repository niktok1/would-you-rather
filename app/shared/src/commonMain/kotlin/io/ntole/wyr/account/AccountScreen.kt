package io.ntole.wyr.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.ntole.wyr.core.domain.account.AccountRules
import io.ntole.wyr.core.domain.account.PasswordProblem
import io.ntole.wyr.core.domain.account.UsernameProblem
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/**
 * The Account screen (CLAUDE.md §8d, *Current focus*): who is playing on this device and their
 * stats, the points first; for a guest, Register, which keeps the points, and Log in, to an account
 * registered anywhere; for a registered player, Log out. A build for any server but production's
 * names that server last ([serverLine]), [environment] being the one the build talks to.
 *
 * Plain on purpose while UI polish is paused, and every colour, space and size from the theme (§5b).
 * Each field names its autofill content type, so the platform's password manager can offer to fill
 * it, and to save what was typed once Register or Log in takes the forms off the screen.
 */
@Composable
fun AccountScreen(
    state: AccountState,
    actions: AccountActions,
    environment: WyrEnvironment,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Surface(color = colors.pageBackground, modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceLg),
        ) {
            Text(
                text = "Account",
                color = colors.headingAccent,
                fontSize = WyrTypeScale.heading,
                fontWeight = FontWeight.ExtraBold,
            )

            Status(state, actions)

            val stats = state.stats
            when {
                stats == null -> {}

                stats.username == null -> {
                    RegisterForm(state, actions)
                    LogInForm(state, actions)
                }

                else -> {
                    LogOutSection(state, actions)
                }
            }

            serverLine(environment)?.let { line ->
                Text(text = line, color = colors.muted, fontSize = WyrTypeScale.statLabel)
            }
        }
    }
}

@Composable
private fun Status(
    state: AccountState,
    actions: AccountActions,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
        val stats = state.stats
        val failure = state.failure?.takeIf { it.action == AccountAction.LOAD }
        when {
            stats != null -> {
                Text(text = playingAs(stats), color = colors.primaryText, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
                    statLines(stats).forEach { line ->
                        Text(text = line, color = colors.muted, fontSize = WyrTypeScale.statLabel)
                    }
                }
            }

            failure == null -> {
                CircularProgressIndicator(color = colors.headingAccent)
            }
        }
        if (failure != null) {
            FailureText(failure)
            OutlinedButton(onClick = actions::refresh, enabled = !state.isBusy) { Text("Try again") }
        }
        if (state.isBusy && stats != null) {
            LinearProgressIndicator(color = colors.headingAccent, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RegisterForm(
    state: AccountState,
    actions: AccountActions,
) {
    Section(title = "Register", note = "Keep your points, and play them on any device.") {
        OutlinedTextField(
            value = state.registerUsername,
            onValueChange = actions::setRegisterUsername,
            label = { Text("Username") },
            supportingText = { Text(usernameHint(state.usernameProblem)) },
            isError = state.usernameProblem != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewUsername },
        )
        OutlinedTextField(
            value = state.registerPassword,
            onValueChange = actions::setRegisterPassword,
            label = { Text("Password") },
            supportingText = { Text(passwordHint(state.passwordProblem)) },
            isError = state.passwordProblem != null,
            singleLine = true,
            visualTransformation =
                if (state.showRegisterPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            trailingIcon = {
                TextButton(onClick = actions::toggleShowRegisterPassword) {
                    Text(if (state.showRegisterPassword) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword },
        )
        FailureOf(state, AccountAction.REGISTER)
        Button(onClick = actions::register, enabled = state.canRegister, modifier = Modifier.fillMaxWidth()) {
            Text("Register")
        }
    }
}

@Composable
private fun LogInForm(
    state: AccountState,
    actions: AccountActions,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Section(title = "Log in", note = "Registered already? Log in to play as your account on this device.") {
        OutlinedTextField(
            value = state.loginUsername,
            onValueChange = actions::setLoginUsername,
            label = { Text("Username") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
        )
        OutlinedTextField(
            value = state.loginPassword,
            onValueChange = actions::setLoginPassword,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
        )
        FailureOf(state, AccountAction.LOG_IN)

        val warning = state.guestPointsWarning
        if (warning == null) {
            Button(onClick = actions::logIn, enabled = state.canLogIn, modifier = Modifier.fillMaxWidth()) {
                Text("Log in")
            }
        } else {
            Text(text = guestProgressWarning(warning), color = colors.primaryText)
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
                Button(onClick = actions::logIn, enabled = state.canLogIn) { Text("Log in anyway") }
                OutlinedButton(onClick = actions::cancelLogIn, enabled = !state.isBusy) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun LogOutSection(
    state: AccountState,
    actions: AccountActions,
) {
    Section(
        title = "Log out",
        note = "This device goes back to a new guest. Log in again any time with your username and password.",
    ) {
        FailureOf(state, AccountAction.LOG_OUT)
        OutlinedButton(onClick = actions::logOut, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("Log out")
        }
    }
}

@Composable
private fun Section(
    title: String,
    note: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = WyrThemeAccessors.colors

    Column(verticalArrangement = Arrangement.spacedBy(WyrThemeAccessors.dimens.spaceSm)) {
        Text(
            text = title,
            color = colors.primaryText,
            fontSize = WyrTypeScale.sectionTitle,
            fontWeight = FontWeight.Bold,
        )
        Text(text = note, color = colors.muted, fontSize = WyrTypeScale.statLabel)
        content()
    }
}

/** The failure of [action], under the section that sent it, when the last action to fail was it. */
@Composable
private fun FailureOf(
    state: AccountState,
    action: AccountAction,
) {
    state.failure?.takeIf { it.action == action }?.let { FailureText(it) }
}

@Composable
private fun FailureText(failure: AccountFailure) {
    Text(text = failureMessage(failure), color = MaterialTheme.colorScheme.error)
}

/** Who is playing on this device. */
internal fun playingAs(stats: PlayerStats): String = stats.username?.let { "Logged in as $it" } ?: "Playing as guest"

internal fun pointsOf(stats: PlayerStats): String = pointsText(stats.totalPoints)

/**
 * The player's stats, a line each, as the server counted them (CLAUDE.md §8d, *Stats*): the points,
 * the answers given and the questions they went to (a re-answer is one more answer to the same
 * question), the cycle and how many questions are left in it, neither answered nor skipped, and the
 * likes the questions the player submitted hold. Nothing is worked out here.
 */
internal fun statLines(stats: PlayerStats): List<String> =
    listOf(
        pointsOf(stats),
        "${counted(stats.answersGiven, "answer")} to ${counted(stats.questionsAnswered, "question")}",
        "Cycle ${stats.cycle}: ${counted(stats.dueThisCycle, "question")} left",
        "${counted(stats.likesReceived, "like")} on questions you submitted",
    )

/**
 * The server a LOCAL or DEV build talks to, by name and URL, so a tester can tell which one they are
 * on (CLAUDE.md §8e), or null in a PROD build, whose players have no other server to tell it from.
 */
internal fun serverLine(environment: WyrEnvironment): String? =
    if (environment == WyrEnvironment.PROD) null else "Server: ${environment.displayName} (${environment.apiBaseUrl})"

/** The username rule, or what is wrong with the name typed by it. */
internal fun usernameHint(problem: UsernameProblem?): String =
    when (problem) {
        null -> {
            "${AccountRules.MIN_USERNAME_LENGTH} to ${AccountRules.MAX_USERNAME_LENGTH} letters, digits or _"
        }

        UsernameProblem.TOO_SHORT -> {
            "At least ${AccountRules.MIN_USERNAME_LENGTH} characters"
        }

        UsernameProblem.TOO_LONG -> {
            "At most ${AccountRules.MAX_USERNAME_LENGTH} characters"
        }

        UsernameProblem.INVALID_CHARACTER -> {
            "Only letters a to z, digits and _, with no spaces"
        }
    }

/** The password rule, or what is wrong with the password typed by it. */
internal fun passwordHint(problem: PasswordProblem?): String =
    when (problem) {
        null -> "At least ${AccountRules.MIN_PASSWORD_LENGTH} characters"
        PasswordProblem.TOO_SHORT -> "At least ${AccountRules.MIN_PASSWORD_LENGTH} characters"
        PasswordProblem.TOO_LONG -> "At most ${AccountRules.MAX_PASSWORD_LENGTH} characters"
    }

/** The one warning a guest with points gets before a login leaves them behind. */
internal fun guestProgressWarning(points: Int): String =
    "Your ${pointsText(points)} as a guest stay behind on this guest if you log in: register first to keep them."

private fun pointsText(points: Int): String = counted(points, "point")

/** [count] of [noun], which takes an s but for one. */
private fun counted(
    count: Int,
    noun: String,
): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/**
 * Player-facing copy for a failed action, by its [DomainError], never the server's message, which is
 * diagnostic only.
 */
internal fun failureMessage(failure: AccountFailure): String =
    when (failure.error) {
        DomainError.USERNAME_TAKEN -> {
            "That username is taken. Try another."
        }

        DomainError.INVALID_LOGIN -> {
            "Wrong username or password."
        }

        DomainError.ALREADY_REGISTERED -> {
            "You're registered already."
        }

        DomainError.INVALID_USERNAME -> {
            "The server can't take that username."
        }

        DomainError.INVALID_PASSWORD -> {
            "The server can't take that password."
        }

        DomainError.RATE_LIMITED -> {
            val wait = failure.retryAfter?.let { "Wait ${it.inWholeSeconds} s" } ?: "Wait a moment"
            "Too many tries. $wait, then try again."
        }

        DomainError.NETWORK -> {
            "Can't reach the game. Check your connection."
        }

        else -> {
            "Something went wrong. Try again."
        }
    }

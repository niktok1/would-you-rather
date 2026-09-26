package io.ntole.wyr.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.core.domain.account.AccountRules
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.language.AccountStrings
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.USERNAME_CHARACTERS
import io.ntole.wyr.language.fill
import io.ntole.wyr.points.PointsText
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.contentWidth

/**
 * The Auth page (CLAUDE.md §8d, *The Account screen*), opened by a guest's one button on the Account
 * screen: Register, which keeps the guest's points, and a link that switches the same page to Log in,
 * with a link back ([AccountState.authMode]). A register or a login that worked raises
 * [AccountState.signedIn], on which the app goes back to the Account screen. Shown before any player
 * is read, a read that failed says so on top, with Try again.
 *
 * Plain on purpose, every colour, space and size from the theme (§5b), and every word from
 * [LocalStrings] (§8f). Each field names its autofill content type, so the platform's password manager
 * can offer to fill it, and to save what was typed once the page leaves the screen.
 */
@Composable
fun AuthScreen(
    state: AccountState,
    actions: AccountActions,
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
                    .padding(dimens.screenPadding)
                    .contentWidth(dimens.contentMaxWidth),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        ) {
            ReadFailure(state, actions)
            when (state.authMode) {
                AuthMode.REGISTER -> RegisterForm(state, actions)
                AuthMode.LOG_IN -> LogInForm(state, actions)
            }
            if (state.isBusy) {
                LinearProgressIndicator(color = colors.headingAccent, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RegisterForm(
    state: AccountState,
    actions: AccountActions,
) {
    val strings = LocalStrings.current.accountScreens

    OutlinedTextField(
        value = state.registerUsername,
        onValueChange = actions::setRegisterUsername,
        label = { Text(strings.username) },
        supportingText = { Text(usernameRule(strings)) },
        isError = state.usernameProblem != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewUsername },
    )
    OutlinedTextField(
        value = state.registerPassword,
        onValueChange = actions::setRegisterPassword,
        label = { Text(strings.password) },
        supportingText = { Text(passwordRule(strings)) },
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
            TextButton(onClick = tapped("auth.show_password", onClick = actions::toggleShowRegisterPassword)) {
                Text(if (state.showRegisterPassword) strings.hide else strings.show)
            }
        },
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword },
    )
    FailureOf(state, AccountAction.REGISTER)
    Button(
        onClick = tapped("auth.register", onClick = actions::register),
        enabled = state.canRegister,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(strings.register)
    }
    TextButton(onClick = tapped("auth.to_log_in") { actions.setAuthMode(AuthMode.LOG_IN) }, enabled = !state.isBusy) {
        Text(strings.toLogIn)
    }
}

@Composable
private fun LogInForm(
    state: AccountState,
    actions: AccountActions,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.accountScreens

    OutlinedTextField(
        value = state.loginUsername,
        onValueChange = actions::setLoginUsername,
        label = { Text(strings.username) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
    )
    OutlinedTextField(
        value = state.loginPassword,
        onValueChange = actions::setLoginPassword,
        label = { Text(strings.password) },
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
        Button(
            onClick = tapped("auth.log_in", onClick = actions::logIn),
            enabled = state.canLogIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.logIn)
        }
    } else {
        PointsText(
            template = strings.guestPointsWarning,
            points = warning,
            spoken = strings.guestPointsWarning.fill(warning),
            color = colors.primaryText,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
            Button(onClick = tapped("auth.log_in_anyway", onClick = actions::logIn), enabled = state.canLogIn) {
                Text(strings.logInAnyway)
            }
            OutlinedButton(onClick = tapped("auth.cancel", onClick = actions::cancelLogIn), enabled = !state.isBusy) {
                Text(LocalStrings.current.cancel)
            }
        }
    }
    TextButton(
        onClick = tapped("auth.to_register") { actions.setAuthMode(AuthMode.REGISTER) },
        enabled = !state.isBusy,
    ) {
        Text(strings.toRegister)
    }
}

/**
 * Why no player could be read, with Try again, while none is: Log in waits on one, since the warning
 * names the points a login leaves behind ([AccountState.canLogIn]).
 */
@Composable
private fun ReadFailure(
    state: AccountState,
    actions: AccountActions,
) {
    if (state.stats != null) return
    val failure = state.failure?.takeIf { it.action == AccountAction.LOAD } ?: return
    FailureText(failure)
    OutlinedButton(onClick = tapped("auth.try_again", onClick = actions::refresh), enabled = !state.isBusy) {
        Text(LocalStrings.current.tryAgain)
    }
}

/** The failure of [action], under the form that sent it, when the last action to fail was it. */
@Composable
internal fun FailureOf(
    state: AccountState,
    action: AccountAction,
) {
    state.failure?.takeIf { it.action == action }?.let { FailureText(it) }
}

@Composable
internal fun FailureText(failure: AccountFailure) {
    Text(
        text = failureMessage(failure, LocalStrings.current.accountScreens),
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * The username rule, under the field whatever is typed, in the error colour while what is typed
 * breaks it: it names the length and the characters, which is all a name can get wrong.
 */
internal fun usernameRule(strings: AccountStrings): String =
    strings.usernameRule.fill(AccountRules.MIN_USERNAME_LENGTH, AccountRules.MAX_USERNAME_LENGTH, USERNAME_CHARACTERS)

/** The password rule, as [usernameRule] is shown. */
internal fun passwordRule(strings: AccountStrings): String =
    strings.passwordRule.fill(AccountRules.MIN_PASSWORD_LENGTH, AccountRules.MAX_PASSWORD_LENGTH)

/**
 * Player-facing copy for a failed action, by its [DomainError], never the server's message, which is
 * diagnostic only.
 */
internal fun failureMessage(
    failure: AccountFailure,
    strings: AccountStrings,
): String =
    when (failure.error) {
        DomainError.USERNAME_TAKEN -> strings.usernameTaken
        DomainError.INVALID_LOGIN -> strings.wrongLogin
        DomainError.ALREADY_REGISTERED -> strings.alreadyRegistered
        DomainError.INVALID_USERNAME -> strings.usernameRefused
        DomainError.INVALID_PASSWORD -> strings.passwordRefused
        DomainError.RATE_LIMITED -> rateLimited(failure, strings)
        DomainError.NETWORK -> strings.offline
        else -> strings.somethingWrong
    }

private fun rateLimited(
    failure: AccountFailure,
    strings: AccountStrings,
): String = failure.retryAfter?.let { strings.tooManyTries.fill(it.inWholeSeconds) } ?: strings.tooManyTriesNoWait

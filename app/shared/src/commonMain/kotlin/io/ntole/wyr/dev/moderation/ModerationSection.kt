package io.ntole.wyr.dev.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.dev.LogEntry
import io.ntole.wyr.dev.LogResult
import io.ntole.wyr.dev.namesOf
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import org.koin.compose.viewmodel.koinViewModel

/** The *Moderation* section wired to its own ViewModel, for the console to place among its own. */
@Composable
fun ModerationConsole() {
    val viewModel = koinViewModel<ModerationConsoleViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    ModerationSection(
        state = state,
        onAdminTokenChange = viewModel::setAdminToken,
        onLoadPending = viewModel::loadPending,
        onToggleCategory = viewModel::toggleCategory,
        onReasonChange = viewModel::setReason,
        onApprove = viewModel::approve,
        onReject = viewModel::reject,
    )
}

/**
 * Moderating the players' submissions with the server's admin token (CLAUDE.md §8d, *Moderation*):
 * the pending queue, and for each submission the categories to approve it under and a reason to
 * reject it with, with this section's action log in the console's style: `ok`, `err` with the
 * server's diagnostic message, or `crash`.
 *
 * Deliberately as plain as the rest of the console. Its private parts mirror `DevConsoleScreen`'s,
 * which are private to that file.
 */
@Composable
fun ModerationSection(
    state: ModerationConsoleState,
    onAdminTokenChange: (String) -> Unit,
    onLoadPending: () -> Unit,
    onToggleCategory: (questionId: String, category: Category) -> Unit,
    onReasonChange: (questionId: String, reason: String) -> Unit,
    onApprove: (questionId: String) -> Unit,
    onReject: (questionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Every request of this section needs the token, so nothing can be sent without one.
    val canSend = !state.isBusy && state.token != null

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(WyrThemeAccessors.dimens.spaceSm)) {
        HorizontalDivider()
        Text("Moderation", style = MaterialTheme.typography.titleMedium)
        // Masked, and a password to the keyboard, so no keyboard learns it as a word. Its value lives in
        // the ViewModel, not rememberSaveable, whose saved state can be written to disk.
        OutlinedTextField(
            value = state.adminToken.text,
            onValueChange = onAdminTokenChange,
            label = { Text("admin token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Value("token", tokenStatusOf(state.adminToken.text))
        Buttons {
            Button(onClick = onLoadPending, enabled = canSend) { Text("Load pending") }
        }

        val pending = state.pending
        when {
            pending == null -> {
                Value("pending", "not read")
            }

            pending.isEmpty() -> {
                Value("pending", "none")
            }

            else -> {
                pending.forEach { submission ->
                    // By id, so a field's focus stays with its submission when a decided one leaves the list.
                    key(submission.id) {
                        PendingSubmission(
                            submission = submission,
                            state = state,
                            canSend = canSend,
                            onToggleCategory = onToggleCategory,
                            onReasonChange = onReasonChange,
                            onApprove = onApprove,
                            onReject = onReject,
                        )
                    }
                }
            }
        }

        val running = state.running
        if (running != null) {
            Value("running", running)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text("Moderation log", style = MaterialTheme.typography.titleSmall)
        moderationOffHint(state.log)?.let { CodeLine(it) }
        if (state.log.isEmpty()) Value("log", "empty")
        state.log.forEach { entry -> LogLine(entry) }
    }
}

/**
 * One pending submission as the server sent it, the categories to approve it under, and the reason
 * to reject it with. Reject stays off until the reason is one the server accepts.
 */
@Composable
private fun PendingSubmission(
    submission: Submission,
    state: ModerationConsoleState,
    canSend: Boolean,
    onToggleCategory: (questionId: String, category: Category) -> Unit,
    onReasonChange: (questionId: String, reason: String) -> Unit,
    onApprove: (questionId: String) -> Unit,
    onReject: (questionId: String) -> Unit,
) {
    val id = submission.id
    val picked = state.newCategoriesOf(id)

    Column(verticalArrangement = Arrangement.spacedBy(WyrThemeAccessors.dimens.spaceXs)) {
        pendingLines(submission).forEach { CodeLine(it) }
        Value("approve under", approvalOf(picked))
        Buttons {
            Category.selectable.forEach { category ->
                FilterChip(
                    selected = category in picked,
                    onClick = { onToggleCategory(id, category) },
                    label = { Text(category.name) },
                )
            }
        }
        // One line, as the server holds a reason to one.
        OutlinedTextField(
            value = state.reasonOf(id),
            onValueChange = { onReasonChange(id, it) },
            label = { Text("reason, one line of at most ${RejectionReason.MAX_LENGTH}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Buttons {
            Button(onClick = { onApprove(id) }, enabled = canSend) { Text("Approve") }
            OutlinedButton(onClick = { onReject(id) }, enabled = canSend && state.rejectionOf(id) != null) {
                Text("Reject")
            }
        }
    }
}

/** Whether what is typed can be sent as the token, without ever showing any of it. */
internal fun tokenStatusOf(typed: String): String =
    when {
        typed.isBlank() -> "none typed"
        AdminToken.of(typed) == null -> "cannot be a token: visible ASCII only, no spaces"
        else -> "ready"
    }

/** A submission as the server sent it, a line a field, as the author's own list shows one. */
internal fun pendingLines(submission: Submission): List<String> =
    buildList {
        add("${submission.id} ${submission.status} submittedAt=${submission.submittedAt}")
        add("  categories: ${namesOf(submission.categories)}")
        add("  A: ${submission.optionA}")
        add("  B: ${submission.optionB}")
        // The server sends a reason only with a rejection, but this console shows what it sent.
        submission.rejectionReason?.let { add("  reason: $it") }
    }

/** What an approval with [picked] files the submission under: the author's own when none are picked. */
internal fun approvalOf(picked: Set<Category>): String =
    if (picked.isEmpty()) "the author's categories" else namesOf(picked)

/**
 * A reading of the newest entry in [log] when it is `UNKNOWN`, which is how a server with moderation
 * off answers: it has no admin routes, so each is a bare 404 with no code (CLAUDE.md §8d). Otherwise
 * nothing.
 */
internal fun moderationOffHint(log: List<LogEntry>): String? {
    val newest = log.firstOrNull()?.result
    if (newest !is LogResult.Err || newest.error != DomainError.UNKNOWN) return null
    return "UNKNOWN with a 404 in the HTTP trace: moderation is off on this server (no ADMIN_TOKEN)"
}

@Composable
private fun LogLine(entry: LogEntry) {
    val (outcome, failed) =
        when (val result = entry.result) {
            is LogResult.Ok -> "ok ${result.summary}" to false
            is LogResult.Err -> "err ${result.error} ${result.message.orEmpty()}" to true
            is LogResult.Crash -> "crash ${result.type} ${result.message.orEmpty()}" to true
        }
    CodeLine("${entry.action}(${entry.args}) ${entry.elapsedMillis}ms -> $outcome", failed)
}

@Composable
private fun CodeLine(
    text: String,
    failed: Boolean = false,
) {
    val color = if (failed) MaterialTheme.colorScheme.error else LocalContentColor.current
    Text(text = text, style = WyrTypeScale.code, color = color)
}

@Composable
private fun Value(
    label: String,
    value: String,
) {
    Text(text = "$label: $value", style = WyrTypeScale.code)
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    val dimens = WyrThemeAccessors.dimens
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
    ) {
        content()
    }
}

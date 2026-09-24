package io.ntole.wyr.dev.submission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.dev.LogEntry
import io.ntole.wyr.dev.LogResult
import io.ntole.wyr.dev.namesOf
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import org.koin.compose.viewmodel.koinViewModel

/** The *Submit a question* section wired to its own ViewModel, for the console to place among its own. */
@Composable
fun SubmissionConsole() {
    val viewModel = koinViewModel<SubmissionConsoleViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    SubmissionSection(
        state = state,
        onOptionAChange = viewModel::setOptionA,
        onOptionBChange = viewModel::setOptionB,
        onToggleCategory = viewModel::toggleCategory,
        onSubmit = viewModel::submit,
        onRefresh = viewModel::listSubmissions,
    )
}

/**
 * Writing a question, submitting it, and the author's own submissions (CLAUDE.md §8d, *Submitting*),
 * with this section's action log in the console's style: `ok`, `err` with the server's diagnostic
 * message (a 422's says which rule the options broke), or `crash`.
 *
 * Deliberately as plain as the rest of the console. Its private parts mirror `DevConsoleScreen`'s,
 * which are private to that file.
 */
@Composable
fun SubmissionSection(
    state: SubmissionConsoleState,
    onOptionAChange: (String) -> Unit,
    onOptionBChange: (String) -> Unit,
    onToggleCategory: (Category) -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = !state.isBusy

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(WyrThemeAccessors.dimens.spaceSm)) {
        HorizontalDivider()
        Text("Submit a question", style = MaterialTheme.typography.titleMedium)
        // Not singleLine: a line break is one of the rules the server enforces (422), and this console
        // is where to provoke it. The fields stay open while a submission is in flight; what is typed
        // meanwhile is kept.
        OutlinedTextField(
            value = state.optionA,
            onValueChange = onOptionAChange,
            label = { Text("option A") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.optionB,
            onValueChange = onOptionBChange,
            label = { Text("option B") },
            modifier = Modifier.fillMaxWidth(),
        )
        Value("categories", pickedOf(state.categories))
        Buttons {
            Category.selectable.forEach { category ->
                FilterChip(
                    selected = category in state.categories,
                    onClick = { onToggleCategory(category) },
                    label = { Text(category.name) },
                )
            }
        }
        Buttons {
            Button(onClick = onSubmit, enabled = idle && state.canSubmit) { Text("Submit") }
        }

        Text("My submissions", style = MaterialTheme.typography.titleSmall)
        Value("listed for", state.listedFor ?: "not read")
        Buttons {
            OutlinedButton(onClick = onRefresh, enabled = idle) { Text("Refresh my submissions") }
        }
        val submissions = state.submissions
        when {
            submissions == null -> Value("submissions", "not read")
            submissions.isEmpty() -> Value("submissions", "none")
            else -> submissions.forEach { submission -> submissionLines(submission).forEach { CodeLine(it) } }
        }

        val running = state.running
        if (running != null) {
            Value("running", running)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text("Submission log", style = MaterialTheme.typography.titleSmall)
        if (state.log.isEmpty()) Value("log", "empty")
        state.log.forEach { entry -> LogLine(entry) }
    }
}

/** The categories picked for the question, or that none is, which Submit waits for. */
internal fun pickedOf(categories: Set<Category>): String =
    if (categories.isEmpty()) "none picked" else namesOf(categories)

/**
 * One submission as the server sent it, a line a field. The server sends a reason only with a
 * rejection; a rejection without one says so, and a reason on any other status is shown too, since
 * this console shows what the server sent.
 */
internal fun submissionLines(submission: Submission): List<String> =
    buildList {
        add("${submission.id} ${submission.status} submittedAt=${submission.submittedAt}")
        add("  categories: ${namesOf(submission.categories)}")
        add("  A: ${submission.optionA}")
        add("  B: ${submission.optionB}")
        val reason = submission.rejectionReason
        if (submission.status == SubmissionStatus.REJECTED || reason != null) add("  reason: ${reason ?: "none given"}")
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

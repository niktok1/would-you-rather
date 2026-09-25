package io.ntole.wyr.admin.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.ntole.wyr.admin.theme.AdminDimens
import io.ntole.wyr.admin.theme.AdminType

/** Why an action failed, in the error color, with the server's own diagnostic line under it. */
@Composable
fun FailureLine(failure: Failure) {
    Column(verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs)) {
        Text(text = describe(failure), color = MaterialTheme.colorScheme.error)
        val detail = (failure as? Failure.Refused)?.detail
        if (detail != null) {
            Text(text = "server: $detail", style = AdminType.code, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Why the categories could not be read, above a screen whose chips and names they are: the categories
 * read before, if any, stay listed, and a category not read shows by its id.
 */
@Composable
fun CategoriesFailure(failure: Failure) {
    Column(verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs)) {
        Text(text = "The categories could not be read:", style = MaterialTheme.typography.labelLarge)
        FailureLine(failure)
    }
}

/** What the last action that worked did. */
@Composable
fun NoticeLine(notice: String) {
    Text(text = notice, color = MaterialTheme.colorScheme.primary)
}

/**
 * The failures of actions on questions a screen no longer lists, which could otherwise not be seen:
 * a decision another moderator beat, say, whose submission the read after it took off the queue.
 */
@Composable
fun UnlistedFailures(
    failures: Map<String, ItemFailure>,
    listed: Set<String>,
) {
    failures.filterKeys { it !in listed }.forEach { (_, failed) ->
        Column(verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs)) {
            Text(text = failed.question, style = MaterialTheme.typography.labelLarge)
            FailureLine(failed.failure)
        }
    }
}

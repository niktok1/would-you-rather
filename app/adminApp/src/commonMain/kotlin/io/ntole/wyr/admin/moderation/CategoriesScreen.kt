package io.ntole.wyr.admin.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.ntole.wyr.admin.theme.AdminDimens
import io.ntole.wyr.admin.theme.AdminType
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRules

/**
 * Every category, oldest first (CLAUDE.md §8d, *Categories*), each with its id and both its names,
 * and a form to add one above them. Rename puts a category's names right in its own card; its id,
 * and so what is filed under it, never changes. Nothing deletes a category.
 */
@Composable
fun CategoriesScreen(
    state: ModerationState,
    actions: ModerationActions,
    modifier: Modifier = Modifier,
) {
    val list = state.categories
    val categories = list.categories

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AdminDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceMd),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = actions::loadCategories, enabled = state.canSend) {
                        Text(if (categories == null) "Load" else "Reload")
                    }
                    Text(text = categoriesSummaryOf(categories), style = MaterialTheme.typography.bodyMedium)
                }
                list.failure?.let { FailureLine(it) }
                list.outcomes.notice?.let { NoticeLine(it) }
                AddCategoryForm(state, actions)
            }
        }
        items(categories.orEmpty(), key = { it.id }) { category ->
            CategoryCard(category, state, actions)
        }
    }
}

/** The category to add: its names, and an id, which the server makes when none is typed. */
@Composable
private fun AddCategoryForm(
    state: ModerationState,
    actions: ModerationActions,
) {
    val draft = state.categories.adding
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AdminDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
        ) {
            Text(text = "Add a category", style = MaterialTheme.typography.titleMedium)
            NameFields(draft, onChange = actions::editNewCategory)
            OutlinedTextField(
                value = draft.id,
                onValueChange = { actions.editNewCategory(draft.copy(id = it)) },
                label = { Text("Id") },
                supportingText = { Text(idHintOf(draft.id)) },
                isError = draft.idToSend?.let { !CategoryRules.isId(it) } ?: false,
                singleLine = true,
                textStyle = AdminType.code,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = actions::saveNewCategory, enabled = state.canSend && draft.isValid) {
                Text(if (state.running?.action == Action.ADD_CATEGORY) "Adding..." else "Add")
            }
            state.categories.addFailure?.let { FailureLine(it) }
        }
    }
}

/** One category: its id and names, or, while it is being renamed, the names typed for it. */
@Composable
private fun CategoryCard(
    category: Category,
    state: ModerationState,
    actions: ModerationActions,
) {
    val renaming = state.categories.renaming?.takeIf { it.id == category.id }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AdminDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
        ) {
            Text(text = category.id, style = AdminType.code, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (renaming == null) {
                Text(text = category.nameSr, style = MaterialTheme.typography.titleMedium)
                Text(text = category.nameEn, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { actions.startRenaming(category.id) }, enabled = !state.isBusy) {
                    Text("Rename...")
                }
            } else {
                NameFields(renaming, onChange = actions::editRenaming)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm)) {
                    Button(onClick = actions::saveRenaming, enabled = state.canSend && renaming.isValid) {
                        Text(if (state.running?.action == Action.RENAME_CATEGORY) "Saving..." else "Save names")
                    }
                    TextButton(onClick = actions::cancelRenaming) { Text("Cancel") }
                }
                state.categories.renameFailure?.let { FailureLine(it) }
            }
        }
    }
}

/** The Serbian and the English name of [draft], each saying what the server's rules refuse in it. */
@Composable
internal fun NameFields(
    draft: CategoryDraft,
    onChange: (CategoryDraft) -> Unit,
) {
    OutlinedTextField(
        value = draft.nameSr,
        onValueChange = { onChange(draft.copy(nameSr = it)) },
        label = { Text("Serbian name, in Cyrillic") },
        supportingText = { Text(nameHintOf(draft.nameSr)) },
        isError = draft.nameSr.isNotEmpty() && !CategoryRules.isName(draft.nameSr),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.nameEn,
        onValueChange = { onChange(draft.copy(nameEn = it)) },
        label = { Text("English name") },
        supportingText = { Text(nameHintOf(draft.nameEn)) },
        isError = draft.nameEn.isNotEmpty() && !CategoryRules.isName(draft.nameEn),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** How many categories there are, or that they have not been read. */
fun categoriesSummaryOf(categories: List<Category>?): String =
    when {
        categories == null -> "Not read yet."
        categories.isEmpty() -> "No categories."
        categories.size == 1 -> "1 category."
        else -> "${categories.size} categories, oldest first."
    }

/** The rule a name is held to, or what is wrong with the one typed by it. */
fun nameHintOf(name: String): String =
    if (name.isEmpty() || CategoryRules.isName(name)) {
        "One line, at most ${CategoryRules.MAX_NAME_LENGTH} characters, trimmed."
    } else {
        "Not a name the server takes: one line of 1 to ${CategoryRules.MAX_NAME_LENGTH} characters."
    }

/** What an id does, blank or typed, or what is wrong with the one typed. */
fun idHintOf(id: String): String =
    when {
        id.isBlank() -> {
            "Blank: the server makes it from the English name (Fast food is FAST_FOOD). A name of no Latin " +
                "letter or digit needs one typed."
        }

        CategoryRules.isId(id) -> {
            "Questions are filed under it, and it never changes."
        }

        else -> {
            "Not an id the server takes: 1 to ${CategoryRules.MAX_ID_LENGTH} of A-Z, 0-9 and _."
        }
    }

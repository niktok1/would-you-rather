package io.ntole.wyr.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import io.ntole.wyr.language.LocalLanguage
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.categoryName
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/**
 * The Categories screen (CLAUDE.md §8d, *Categories*), opened from the Play screen under a top bar of
 * a back arrow, which leaves it without playing anything: the search field, then one list, All first
 * and every category the search finds under it, each ticked or not, and at the bottom how many are
 * ticked and Play. Categories are named in the language shown ([categoryName], [LocalLanguage]).
 *
 * The list is lazy, so hundreds of categories draw only the lines on screen, and it scrolls between
 * the search field and Play, which stay where they are.
 */
@Composable
fun CategoriesScreen(
    state: CategoriesState,
    actions: CategoriesActions,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.categoriesScreen
    val language = LocalLanguage.current

    Surface(color = colors.pageBackground, contentColor = colors.primaryText, modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = dimens.screenPadding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions::search,
                placeholder = { Text(text = strings.search, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(top = dimens.spaceSm),
            )

            if (state.failure != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = strings.cannotLoad,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = WyrTypeScale.statLabel,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = actions::refresh, enabled = !state.isLoading) { Text(strings.tryAgain) }
                }
            }

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                item(key = ALL_KEY) {
                    Option(
                        label = strings.all,
                        ticked = state.ticked.isEmpty(),
                        enabled = !state.isPlaying,
                        bold = true,
                        onClick = actions::selectAll,
                    )
                }
                items(state.found, key = { it.id }) { category ->
                    Option(
                        label = categoryName(category, language),
                        ticked = category.id in state.ticked,
                        enabled = !state.isPlaying,
                        onClick = { actions.toggle(category.id) },
                    )
                }
                // Under All when nothing is listed: that the search found none, or a spinner while the
                // categories are read with none read before. A failed read says so above the list.
                if (state.found.isEmpty() && state.categories.isNotEmpty()) {
                    item(key = NOTE_KEY) { Note { Text(text = strings.noMatch, color = colors.muted) } }
                } else if (state.found.isEmpty() && state.isLoading) {
                    item(key = NOTE_KEY) { Note { Spinner() } }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
                modifier = Modifier.fillMaxWidth().padding(vertical = dimens.spaceSm),
            ) {
                // Nothing while none is ticked: All, first in the list, is ticked then.
                if (state.ticked.isEmpty()) {
                    Spacer(Modifier.weight(1f))
                } else {
                    Text(
                        text = strings.selectedCount(state.ticked.size),
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(onClick = actions::play, enabled = !state.isPlaying) { Text(strings.play, maxLines = 1) }
            }
        }
    }
}

/** A line under All when nothing is listed, [content] in its middle. */
@Composable
private fun Note(content: @Composable () -> Unit) {
    val dimens = WyrThemeAccessors.dimens

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(dimens.spaceMd)) { content() }
}

/** The spinner while the categories are read, named for a screen reader. */
@Composable
private fun Spinner() {
    val name = LocalStrings.current.categoriesScreen.loading
    CircularProgressIndicator(
        color = WyrThemeAccessors.colors.headingAccent,
        modifier = Modifier.semantics { contentDescription = name },
    )
}

/**
 * One line of the list, ticked or not; the whole line toggles it, and is at least as tall as a touch
 * target, which a checkbox without a click of its own is not. A long name wraps rather than being cut.
 */
@Composable
private fun Option(
    label: String,
    ticked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    bold: Boolean = false,
) {
    val dimens = WyrThemeAccessors.dimens

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = ticked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
                .minimumInteractiveComponentSize(),
    ) {
        // No click of its own: the line's toggleable is the one.
        Checkbox(checked = ticked, onCheckedChange = null, enabled = enabled)
        Text(text = label, fontWeight = if (bold) FontWeight.Bold else null)
    }
}

/** List keys no category id can be: ids are capitals, digits and `_` (CLAUDE.md §8d, *Categories*). */
private const val ALL_KEY = "all"
private const val NOTE_KEY = "note"

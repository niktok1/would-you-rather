package io.ntole.wyr.language

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.ntole.wyr.theme.WyrThemeAccessors

/**
 * The language switch (CLAUDE.md §8f): every [Language], each named in itself, [selected] the one
 * the game is shown in. A tap on another calls [onSelect] with it, and the game changes at once.
 *
 * No label on screen, the three names saying what the switch is; a screen reader hears it named in
 * the language shown. No tick on the selected one either, whose colours mark it, so each name keeps
 * the width it needs on a phone.
 */
@Composable
fun LanguageSwitch(
    selected: Language,
    onSelect: (Language) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val label = LocalStrings.current.language
    val languages = Language.entries

    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth().semantics { contentDescription = label }) {
        languages.forEachIndexed { index, language ->
            SegmentedButton(
                selected = language == selected,
                onClick = { onSelect(language) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = languages.size),
                colors =
                    SegmentedButtonDefaults.colors(
                        activeContainerColor = colors.orPillBackground,
                        activeContentColor = colors.orPillText,
                        activeBorderColor = colors.headingAccent,
                        inactiveContainerColor = colors.surface,
                        inactiveContentColor = colors.primaryText,
                        inactiveBorderColor = colors.muted,
                    ),
                icon = {},
                label = { Text(text = language.ownName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

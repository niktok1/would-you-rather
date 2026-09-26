package io.ntole.wyr.language

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrThemeAccessors

/**
 * The language menu (CLAUDE.md §8f): a globe, the language the game is shown in, [selected], named in
 * itself, and a chevron; a tap opens a menu of every [Language], each named in itself, and a tap on
 * one calls [onSelect] with it, and the game changes at once.
 *
 * A menu, not a row of every language, since there will be more of them than a phone's width takes
 * (the user). The globe is what a player who picked a language they cannot read finds it by, the menu
 * names each language so they find their own, and a screen reader hears it named in the language
 * shown, with the one picked.
 */
@Composable
fun LanguageMenu(
    selected: Language,
    onSelect: (Language) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val label = LocalStrings.current.language
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(dimens.radiusCard),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.DropdownList, onClick = tapped("language.menu") { open = true })
                    .semantics(mergeDescendants = true) { contentDescription = "$label: ${selected.ownName}" },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
                modifier =
                    Modifier
                        .heightIn(min = LocalMinimumInteractiveComponentSize.current)
                        .padding(horizontal = dimens.spaceMd, vertical = dimens.spaceSm),
            ) {
                Icon(imageVector = WyrIcons.Globe, contentDescription = null, tint = colors.headingAccent)
                Text(
                    text = selected.ownName,
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(imageVector = WyrIcons.ChevronDown, contentDescription = null, tint = colors.headingAccent)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Language.entries.forEach { language ->
                val picked = language == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            text = language.ownName,
                            color = if (picked) colors.headingAccent else colors.primaryText,
                            fontWeight = if (picked) FontWeight.Bold else null,
                        )
                    },
                    onClick =
                        tapped("language.option", mapOf(AnalyticsProperty.LANGUAGE to language.tag)) {
                            open = false
                            onSelect(language)
                        },
                    modifier = Modifier.semantics { this.selected = picked },
                )
            }
        }
    }
}

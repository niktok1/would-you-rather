package io.ntole.wyr.play

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.report.ReportReason
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrThemeAccessors

/**
 * The menu about the question on screen, an exclamation mark in the Play screen's row right after the
 * thumbs (CLAUDE.md §8d, *The Play screen*, *Reports*), muted so it stays quiet until it is wanted:
 * *Report question*, which then lists the five reasons, one tap each, *Don't show me this question* and
 * *Don't show this author's questions*, dropping down from the mark. A choice closes it and goes to
 * [onPick]. Off while [enabled] is not, muted still: while anything is in flight, or while a question
 * held as the next one loads is on screen.
 */
@Composable
internal fun QuestionMenu(
    enabled: Boolean,
    onPick: (MenuChoice) -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val strings = LocalStrings.current.playScreen.menu
    var open by remember { mutableStateOf(false) }
    // The reasons, in place of the three choices, once Report is tapped.
    var reporting by remember { mutableStateOf(false) }

    fun close() {
        open = false
        reporting = false
    }

    Box {
        IconButton(onClick = tapped("question_menu.open") { open = true }, enabled = enabled) {
            Icon(
                imageVector = WyrIcons.Report,
                contentDescription = strings.name,
                // Muted, on or off: the row's quiet control, beside the thumbs in the heading's accent.
                tint = colors.muted,
            )
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = ::close) {
            if (reporting) {
                ReportReason.entries.forEach { reason ->
                    MenuLine(
                        text = strings.reason(reason),
                        onClick =
                            tapped("question_menu.reason", mapOf(AnalyticsProperty.REASON to reason.name.lowercase())) {
                                close()
                                onPick(MenuChoice.Report(reason))
                            },
                    )
                }
            } else {
                MenuLine(strings.report, tapped("question_menu.report") { reporting = true })
                MenuLine(
                    strings.hideQuestion,
                    tapped("question_menu.hide_question") {
                        close()
                        onPick(MenuChoice.HideQuestion)
                    },
                )
                MenuLine(
                    strings.hideAuthor,
                    tapped("question_menu.hide_author") {
                        close()
                        onPick(MenuChoice.HideAuthor)
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuLine(
    text: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text = text, color = WyrThemeAccessors.colors.primaryText) },
        onClick = onClick,
    )
}

package io.ntole.wyr.share

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.ShareStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.play.pressScale
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrThemeAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The share icon (CLAUDE.md §8d, *Sharing*), [element] its tap's name, on only while [enabled], and drawn
 * muted while it is not, as Skip is.
 */
@Composable
internal fun ShareButton(
    element: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val interaction = remember { MutableInteractionSource() }

    IconButton(
        onClick = tapped(element, onClick = onClick),
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.pressScale(interaction),
    ) {
        Icon(
            imageVector = WyrIcons.Share,
            contentDescription = LocalStrings.current.playScreen.share.share,
            tint = if (enabled) colors.headingAccent else colors.muted,
        )
    }
}

/**
 * Sharing [question] (CLAUDE.md §8d, *Sharing*): its image as it will go ([ShareCard]), shrunk to the
 * dialog's width; a switch for its results, on at first, while it has them; and Share, which makes the
 * image and hands it, with a line and the game's store page, to [LocalShareSheet]. A share sheet that
 * opened closes the dialog ([onDismiss]); a copy or a download says so, and a failure says it failed,
 * the dialog left open. [onShared] hears what was shared and how, for the analytics.
 */
@Composable
internal fun ShareDialog(
    question: SharedQuestion,
    onDismiss: () -> Unit,
    onShared: (withResults: Boolean, outcome: ShareOutcome) -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.playScreen.share
    val sheet = LocalShareSheet.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var withResults by rememberSaveable { mutableStateOf(question.hasResults) }
    var sending by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ShareOutcome?>(null) }
    val message = strings.message.fill(STORE_URL)
    val toggle = tapped("share.results") { withResults = !withResults }

    Dialog(onDismissRequest = { if (!sending) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(dimens.radiusCard),
            color = colors.surface,
            contentColor = colors.primaryText,
            modifier = Modifier.widthIn(max = dimens.shareDialogMaxWidth),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimens.spaceMd),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(dimens.spaceLg),
            ) {
                ShareCardFrame(layer = layer, modifier = Modifier.clip(RoundedCornerShape(dimens.spaceMd))) {
                    ShareCard(question = question, withResults = withResults)
                }
                if (question.hasResults) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = withResults,
                                    enabled = !sending,
                                    role = Role.Switch,
                                    onValueChange = { toggle() },
                                ).minimumInteractiveComponentSize(),
                    ) {
                        Text(text = strings.showResults, modifier = Modifier.weight(1f))
                        Switch(checked = withResults, onCheckedChange = null, enabled = !sending)
                    }
                }
                outcome?.let { shown ->
                    Text(
                        text = outcomeText(shown, strings),
                        color = if (shown == ShareOutcome.FAILED) MaterialTheme.colorScheme.error else colors.muted,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val done = outcome == ShareOutcome.COPIED || outcome == ShareOutcome.SAVED
                    TextButton(onClick = tapped("share.cancel", onClick = onDismiss), enabled = !sending) {
                        Text(if (done) strings.close else LocalStrings.current.cancel)
                    }
                    Spacer(Modifier.size(dimens.spaceSm))
                    Button(
                        onClick =
                            tapped("share.send") {
                                sending = true
                                outcome = null
                                val shared = withResults && question.hasResults
                                scope.launch {
                                    val result =
                                        try {
                                            sheet.share(layer.toImageBitmap(), message)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            // The image could not be made: nothing was shared.
                                            ShareOutcome.FAILED
                                        }
                                    sending = false
                                    onShared(shared, result)
                                    if (result == ShareOutcome.OPENED) onDismiss() else outcome = result
                                }
                            },
                        enabled = !sending,
                    ) {
                        Icon(imageVector = WyrIcons.Share, contentDescription = null)
                        Spacer(Modifier.size(dimens.spaceSm))
                        Text(strings.share)
                    }
                }
            }
        }
    }
}

/** What the dialog says once sharing did not open a share sheet. */
private fun outcomeText(
    outcome: ShareOutcome,
    strings: ShareStrings,
): String =
    when (outcome) {
        ShareOutcome.COPIED -> strings.copied
        ShareOutcome.SAVED -> strings.saved
        ShareOutcome.FAILED, ShareOutcome.OPENED -> strings.failed
    }

package io.ntole.wyr.admin.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.ntole.wyr.admin.theme.AdminDimens

/**
 * Reasons ready to reject a question with, or to block its author with, one tap each (CLAUDE.md §8d,
 * *Moderation*): in Serbian Cyrillic, since the author reads a reason in the game, while the app's own
 * words stay English. Each is a reason as the server stores one, trimmed and on one line, which
 * `ReadyReasonsTest` holds to `RejectionReason`'s rules. The fifth is the question rules the user
 * decided (CLAUDE.md §8b, *Personalization*): none on religion, politics, health or sexuality.
 */
val READY_REASONS: List<String> =
    listOf(
        "Није избор између две ствари",
        "Увредљиво",
        "Помиње приватну особу",
        "Дупликат",
        "Тема није дозвољена (вера, политика, здравље, сексуалност)",
        "Неразумљиво",
    )

/**
 * A chip for each of [READY_REASONS], which puts it in the reason field through [onPick], for the
 * moderator to send as it is or edit first. The one [typed] reads as is marked.
 */
@Composable
fun ReadyReasonChips(
    typed: String,
    onPick: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs),
    ) {
        READY_REASONS.forEach { reason ->
            FilterChip(selected = typed == reason, onClick = { onPick(reason) }, label = { Text(reason) })
        }
    }
}

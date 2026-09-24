package io.ntole.wyr.admin.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The moderation app's theme, and the one place its colors, spacing and type are defined
 * (CLAUDE.md §5b): Material 3's own light and dark schemes and type scale, with [AdminDimens] beside
 * them, so no screen holds a hex, dp or sp literal.
 *
 * Not the game's `WyrTheme`: that lives in `:app:shared`, which this app must not depend on
 * (CLAUDE.md §3), and the game's brand is no help to a moderator while UI polish is paused. A palette
 * of its own is one `ColorScheme` passed here.
 */
@Composable
fun AdminTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(), content = content)
}

/** Spacing and widths. Same rule as the colors: no raw `dp` in screen code. */
object AdminDimens {
    val spaceXs = 4.dp
    val spaceSm = 8.dp
    val spaceMd = 16.dp
    val spaceLg = 24.dp

    /** The widest the moderation screens grow on a wide window, so a line stays readable. */
    val contentMaxWidth = 960.dp
}

/** Type the Material scale does not name. */
object AdminType {
    /** Ids, URLs and times, in a fixed width so they read as values rather than prose. */
    val code: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
}

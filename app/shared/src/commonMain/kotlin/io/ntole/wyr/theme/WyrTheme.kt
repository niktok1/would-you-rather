package io.ntole.wyr.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalWyrColors = staticCompositionLocalOf { WyrLightColors }
val LocalWyrDimens = staticCompositionLocalOf { WyrDefaultDimens }

/**
 * The app's theme.
 *
 * Provides the custom token sets *and* mirrors them into a Material [MaterialTheme.colorScheme],
 * so stock Material components inherit the palette instead of falling back to purple defaults.
 */
@Composable
fun WyrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) WyrDarkColors else WyrLightColors

    val materialScheme =
        if (darkTheme) {
            darkColorScheme(
                primary = colors.headingAccent,
                onPrimary = colors.pageBackground,
                background = colors.pageBackground,
                onBackground = colors.primaryText,
                surface = colors.surface,
                onSurface = colors.primaryText,
                surfaceVariant = colors.orPillBackground,
                onSurfaceVariant = colors.orPillText,
                error = colors.optionA,
            )
        } else {
            lightColorScheme(
                primary = colors.headingAccent,
                onPrimary = colors.surface,
                background = colors.pageBackground,
                onBackground = colors.primaryText,
                surface = colors.surface,
                onSurface = colors.primaryText,
                surfaceVariant = colors.orPillBackground,
                onSurfaceVariant = colors.orPillText,
                error = colors.optionA,
            )
        }

    CompositionLocalProvider(
        LocalWyrColors provides colors,
        LocalWyrDimens provides WyrDefaultDimens,
    ) {
        MaterialTheme(colorScheme = materialScheme, content = content)
    }
}

/** Shorthand accessors so screens read `WyrTheme.colors.optionA`. */
object WyrThemeAccessors {
    val colors: WyrColors
        @Composable get() = LocalWyrColors.current

    val dimens: WyrDimens
        @Composable get() = LocalWyrDimens.current
}

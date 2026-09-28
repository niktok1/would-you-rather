package io.ntole.wyr.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalWyrColors = staticCompositionLocalOf { WyrLightColors }
val LocalWyrDimens = staticCompositionLocalOf { WyrDefaultDimens }

/** The art the theme worn draws behind every screen ([PageSurface]); none for the game's own. */
val LocalThemeArt = staticCompositionLocalOf<ThemeArt> { ThemeArt.None }

/**
 * The app's theme.
 *
 * Provides the custom token sets *and* mirrors them into a Material [MaterialTheme.colorScheme],
 * so stock Material components inherit the palette instead of falling back to purple defaults.
 *
 * [theme] is the one worn, the game's own unless the player put on one of the shop's (CLAUDE.md §8d,
 * *The shop*), in the device's [darkTheme] mode where it has two.
 */
@Composable
fun WyrTheme(
    theme: GameTheme = GameThemes.Default,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = theme.colors(darkTheme)

    CompositionLocalProvider(
        LocalWyrColors provides colors,
        LocalWyrDimens provides WyrDefaultDimens,
        LocalThemeArt provides theme.art,
    ) {
        MaterialTheme(colorScheme = materialSchemeOf(colors), content = content)
    }
}

/**
 * [colors] mirrored into Material's scheme, so stock components take the palette. Internal, not
 * private, so a test can hold every pair it makes to AA contrast (CLAUDE.md §5b, `WyrContrastTest`).
 */
internal fun materialSchemeOf(colors: WyrColors): ColorScheme =
    if (colors.isDark) {
        darkColorScheme(
            primary = colors.headingAccent,
            onPrimary = colors.pageBackground,
            background = colors.pageBackground,
            onBackground = colors.primaryText,
            surface = colors.surface,
            onSurface = colors.primaryText,
            surfaceVariant = colors.orPillBackground,
            onSurfaceVariant = colors.orPillText,
            error = colors.error,
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
            error = colors.error,
        )
    }

/** Shorthand accessors so screens read `WyrTheme.colors.optionA`. */
object WyrThemeAccessors {
    val colors: WyrColors
        @Composable get() = LocalWyrColors.current

    val dimens: WyrDimens
        @Composable get() = LocalWyrDimens.current
}

package io.ntole.wyr.theme

import androidx.compose.ui.graphics.Color

// The shop's themes (CLAUDE.md §5b, §8d *The shop*): one palette each, with the cards in colours of
// their own, and the art drawn on the page. Every pair holds WCAG AA as the game's own do, card A's
// text included, and every colour of the art keeps the page's text at 4.5 to 1 on it
// (WyrContrastTest), so a line of text over the art reads as it does on the plain page.

/** Neon night: cyan and magenta on the dark, a glowing grid to the horizon under the stars. */
internal val NeonNightColors: WyrColors =
    WyrColors(
        pageBackground = Color(0xFF0B0B1A),
        surface = Color(0xFF17172E),
        primaryText = Color(0xFFEDEBFF),
        headingAccent = Color(0xFF3DE3FF),
        muted = Color(0xFF9C9AC6),
        orPillText = Color(0xFFFF8AEA),
        orPillBackground = Color(0xFF2C1A3C),
        optionA = Color(0xFFB8157A),
        onOptionA = Color(0xFFFFFFFF),
        optionB = Color(0xFF2AD4EE),
        onOptionB = Color(0xFF062830),
        revealTrackOnA = Color(0xFF7E0E53),
        revealTrackOnB = Color(0xFF1C98AB),
        coin = Color(0xFFFFC940),
        onCoin = Color(0xFF3A2A00),
        error = Color(0xFFFF7FA3),
        isDark = true,
    )

internal val NeonNightArt: ThemeArt =
    ThemeArt.NeonGrid(grid = Color(0xFF16384A), glow = Color(0xFF541F53))

/** Ocean: coral and sea foam on deep water, waves along the bottom and bubbles rising. */
internal val OceanColors: WyrColors =
    WyrColors(
        pageBackground = Color(0xFF0A2C36),
        surface = Color(0xFF113D4A),
        primaryText = Color(0xFFE6F7F5),
        headingAccent = Color(0xFFFFA488),
        muted = Color(0xFF93BCC1),
        orPillText = Color(0xFFFFC9B8),
        orPillBackground = Color(0xFF1D4C58),
        optionA = Color(0xFFC2452F),
        onOptionA = Color(0xFFFFFFFF),
        optionB = Color(0xFF62E0C3),
        onOptionB = Color(0xFF05352C),
        revealTrackOnA = Color(0xFF8C2F1F),
        revealTrackOnB = Color(0xFF3FB79B),
        coin = Color(0xFFFFC857),
        onCoin = Color(0xFF3B2A05),
        error = Color(0xFFFF9A9A),
        isDark = true,
    )

internal val OceanArt: ThemeArt =
    ThemeArt.Waves(far = Color(0xFF133E44), near = Color(0xFF1A4C4F), bubbles = Color(0xFF2B4A53))

/** Forest: moss and terracotta on cream, hills along the bottom and leaves in the corner. */
internal val ForestColors: WyrColors =
    WyrColors(
        pageBackground = Color(0xFFF4F0E2),
        surface = Color(0xFFFFFDF5),
        primaryText = Color(0xFF27351B),
        headingAccent = Color(0xFF46652A),
        muted = Color(0xFF5B604D),
        orPillText = Color(0xFF46652A),
        orPillBackground = Color(0xFFE1E7CB),
        optionA = Color(0xFF4A7429),
        onOptionA = Color(0xFFFFFFFF),
        optionB = Color(0xFFA94E27),
        onOptionB = Color(0xFFFFFFFF),
        revealTrackOnA = Color(0xFF34531C),
        revealTrackOnB = Color(0xFF7C391C),
        coin = Color(0xFFE0A93D),
        onCoin = Color(0xFF3A2A08),
        error = Color(0xFFA3372A),
        isDark = false,
    )

internal val ForestArt: ThemeArt =
    ThemeArt.Hills(far = Color(0xFFE1E4CC), near = Color(0xFFD9DCC4), leaves = Color(0xFFE8D6C4))

/** Sunset: violet and gold on a warm dusk, a low sun behind the dunes. */
internal val SunsetColors: WyrColors =
    WyrColors(
        pageBackground = Color(0xFFFFF0E4),
        surface = Color(0xFFFFFFFF),
        primaryText = Color(0xFF3B1D38),
        headingAccent = Color(0xFF85296A),
        muted = Color(0xFF6B5560),
        orPillText = Color(0xFF85296A),
        orPillBackground = Color(0xFFFADCE7),
        optionA = Color(0xFF6A3C9E),
        onOptionA = Color(0xFFFFFFFF),
        optionB = Color(0xFFF2B23A),
        onOptionB = Color(0xFF3B2200),
        revealTrackOnA = Color(0xFF4B2A72),
        revealTrackOnB = Color(0xFFC98B1C),
        coin = Color(0xFFF2B23A),
        onCoin = Color(0xFF3B2200),
        error = Color(0xFFAE2440),
        isDark = false,
    )

internal val SunsetArt: ThemeArt =
    ThemeArt.Dunes(sun = Color(0xFFFAD7A0), far = Color(0xFFFBCDAA), near = Color(0xFFE6D1D8))

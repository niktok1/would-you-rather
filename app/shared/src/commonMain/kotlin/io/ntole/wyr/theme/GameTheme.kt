package io.ntole.wyr.theme

/**
 * A look the game can wear (CLAUDE.md §5b, §8d *The shop*): [id], as the shop sells it, its colours in a
 * light and a dark mode, and the [art] drawn on the page behind every screen. The game's own,
 * [GameThemes.Default], follows the device's light or dark mode; a theme of the shop's is one palette,
 * the same in both, drawn for its art.
 */
class GameTheme(
    val id: String,
    private val light: WyrColors,
    private val dark: WyrColors,
    val art: ThemeArt,
) {
    /** The colours in the device's [darkMode], or the one palette a theme of the shop's has. */
    fun colors(darkMode: Boolean): WyrColors = if (darkMode) dark else light

    /** Every palette this theme has, once each: what `WyrContrastTest` holds to AA. */
    internal val palettes: List<WyrColors> get() = listOf(light, dark).distinct()
}

/** Every theme the game can wear: the free one, and the shop's, in the order the shop shows them. */
object GameThemes {
    /** The free theme's id, which every player owns and the shop never sells. */
    const val DEFAULT_ID: String = "DEFAULT"

    val Default: GameTheme = GameTheme(DEFAULT_ID, WyrLightColors, WyrDarkColors, ThemeArt.None)

    val NeonNight: GameTheme = GameTheme("NEON_NIGHT", NeonNightColors, NeonNightColors, NeonNightArt)

    val Ocean: GameTheme = GameTheme("OCEAN", OceanColors, OceanColors, OceanArt)

    val Forest: GameTheme = GameTheme("FOREST", ForestColors, ForestColors, ForestArt)

    val Sunset: GameTheme = GameTheme("SUNSET", SunsetColors, SunsetColors, SunsetArt)

    /** The free theme first, then the shop's, by the ids the server sells them under (`ShopCatalog`). */
    val ALL: List<GameTheme> = listOf(Default, NeonNight, Ocean, Forest, Sunset)

    /** The theme of [id], or null for one this build has no colours for. */
    fun ofId(id: String?): GameTheme? = ALL.firstOrNull { it.id == id }
}

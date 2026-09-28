package io.ntole.wyr.theme

/**
 * A look the game can wear (CLAUDE.md §5b, §8d *The shop*): [id], as the shop sells it, its colours in a
 * light and a dark mode, and the art drawn on the page behind every screen in each. The game's own,
 * [GameThemes.Default], follows the device's light or dark mode, its art with it; a theme of the shop's
 * is one palette and one art, the same in both.
 */
class GameTheme(
    val id: String,
    private val light: WyrColors,
    private val dark: WyrColors,
    private val lightArt: ThemeArt,
    private val darkArt: ThemeArt = lightArt,
) {
    /** The colours in the device's [darkMode], or the one palette a theme of the shop's has. */
    fun colors(darkMode: Boolean): WyrColors = if (darkMode) dark else light

    /** The art drawn behind every screen in the device's [darkMode], or the one a theme of the shop's has. */
    fun art(darkMode: Boolean): ThemeArt = if (darkMode) darkArt else lightArt

    /** Every palette this theme has, once each: what `WyrContrastTest` holds to AA. */
    internal val palettes: List<WyrColors> get() = listOf(light, dark).distinct()

    /** Every palette this theme has with the art drawn under it, once each: text on the art is held to AA. */
    internal val looks: List<Pair<WyrColors, ThemeArt>> get() = listOf(light to lightArt, dark to darkArt).distinct()
}

/** Every theme the game can wear: the free one, and the shop's, in the order the shop shows them. */
object GameThemes {
    /** The free theme's id, which every player owns and the shop never sells. */
    const val DEFAULT_ID: String = "DEFAULT"

    val Default: GameTheme = GameTheme(DEFAULT_ID, WyrLightColors, WyrDarkColors, WyrLightArt, WyrDarkArt)

    val NeonNight: GameTheme = GameTheme("NEON_NIGHT", NeonNightColors, NeonNightColors, NeonNightArt)

    val Ocean: GameTheme = GameTheme("OCEAN", OceanColors, OceanColors, OceanArt)

    val Forest: GameTheme = GameTheme("FOREST", ForestColors, ForestColors, ForestArt)

    val Sunset: GameTheme = GameTheme("SUNSET", SunsetColors, SunsetColors, SunsetArt)

    /** The free theme first, then the shop's, by the ids the server sells them under (`ShopCatalog`). */
    val ALL: List<GameTheme> = listOf(Default, NeonNight, Ocean, Forest, Sunset)

    /** The theme of [id], or null for one this build has no colours for. */
    fun ofId(id: String?): GameTheme? = ALL.firstOrNull { it.id == id }
}

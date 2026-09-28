package io.ntole.wyr.shop

import io.ntole.wyr.language.ShopStrings
import io.ntole.wyr.theme.GameThemes

/** The name the shop gives the theme of [id] in the language [strings] are in, or the id for one it has none for. */
fun themeName(
    id: String,
    strings: ShopStrings,
): String =
    when (id) {
        GameThemes.DEFAULT_ID -> strings.classic
        GameThemes.NeonNight.id -> strings.neonNight
        GameThemes.Ocean.id -> strings.ocean
        GameThemes.Forest.id -> strings.forest
        GameThemes.Sunset.id -> strings.sunset
        else -> id
    }

package io.ntole.wyr.core.shop

import kotlinx.serialization.Serializable

/**
 * One theme on sale (CLAUDE.md §8d, *The shop*): its [id], a plain string of `A`-`Z`, `0`-`9` and `_`,
 * never an enum (CLAUDE.md §5), what it costs in points, [price], and whether the player [owned] it
 * already. The palette and the art are the client's: an id this build has none for is a theme it does
 * not show, never a payload it fails to decode.
 */
@Serializable
public data class ShopThemeDto(
    public val id: String,
    public val price: Int = 0,
    public val owned: Boolean = false,
)

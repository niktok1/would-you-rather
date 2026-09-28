package io.ntole.wyr.core.domain.shop

/**
 * A theme on sale: its [id], what it costs in points, [price], and whether the player [owned] it
 * already. Its colours and its art are the game's, found by the id; one the game has none for is
 * not shown.
 */
public data class ShopTheme(
    public val id: String,
    public val price: Int,
    public val owned: Boolean,
)

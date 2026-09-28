package io.ntole.wyr.core.domain.shop

/**
 * The shop as the player on this device sees it (CLAUDE.md §8d, *The shop*): the [themes] on sale, in
 * the server's order, the player's [points], and whether they are [registered], since only a registered
 * player may buy.
 */
public data class Shop(
    public val themes: List<ShopTheme>,
    public val points: Int,
    public val registered: Boolean,
) {
    /** The theme of [id], or null for one the shop does not sell. */
    public fun theme(id: String): ShopTheme? = themes.firstOrNull { it.id == id }
}

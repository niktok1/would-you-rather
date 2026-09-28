package io.ntole.wyr.core.shop

import kotlinx.serialization.Serializable

/**
 * What the shop sells, as the player the bearer token names sees it (CLAUDE.md §8d, *The shop*):
 * [themes], in the order the server lists them, the player's [totalPoints], so the shop can say what
 * they can afford without another read, and whether they are [registered], a username or a Play Games
 * link, since only a registered player may buy.
 *
 * Every field has a default, so a field a server stops sending reads as none rather than failing to
 * decode; a new kind of item is a list of its own beside [themes], which an installed client ignores.
 */
@Serializable
public data class ShopDto(
    public val themes: List<ShopThemeDto> = emptyList(),
    public val totalPoints: Int = 0,
    public val registered: Boolean = false,
)

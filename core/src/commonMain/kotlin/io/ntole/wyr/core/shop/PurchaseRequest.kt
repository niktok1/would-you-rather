package io.ntole.wyr.core.shop

import kotlinx.serialization.Serializable

/**
 * Buys one item of the shop's with a POST to [io.ntole.wyr.core.api.WyrApi.Paths.MY_PURCHASES]
 * (CLAUDE.md §8d, *The shop*): [itemId] is a [ShopThemeDto.id]. Carries no player and no price: the
 * player is whoever the bearer token names, and the price is the server's.
 */
@Serializable
public data class PurchaseRequest(
    public val itemId: String,
)

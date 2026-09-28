package io.ntole.wyr.server.shop

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.server.plugins.ApiFailure

/**
 * What the shop sells (CLAUDE.md §8d, *The shop*): the themes, by id, in the order every shop the
 * server sends lists them. The default theme is every player's, free, and not on sale, so it is not
 * here. Written here rather than stored: a new theme needs a client that draws it, so it comes with a
 * build anyway. The palettes and the art are the clients'; the server knows a theme only by its id.
 *
 * Every theme costs the same, the server's `THEME_PRICE` (`ServerConfig.themePrice`), and a purchase
 * keeps what it paid (`Purchases.price`), so changing the price changes nothing already bought.
 */
object ShopCatalog {
    /** Every theme on sale, in the order the shop lists them. Ids never change once sold. */
    val THEMES: List<String> = listOf("NEON_NIGHT", "OCEAN", "FOREST", "SUNSET")

    /** What a theme costs, in points, when the server's `THEME_PRICE` is unset. */
    const val DEFAULT_THEME_PRICE: Int = 220

    /**
     * [raw] as an item id, 1 to [WyrApi.Limits.MAX_SHOP_ITEM_ID_LENGTH] of `A`-`Z`, `0`-`9` and `_`, as a
     * category's id is, or 400: no correct client sends another. Well formed is not on sale: an id the
     * catalog lacks is the store's 404 ([ShopStore.buy]).
     */
    fun checkedItemId(raw: String): String {
        if (!ITEM_ID.matches(raw)) {
            throw ApiFailure.validation(
                "an item id is 1 to ${WyrApi.Limits.MAX_SHOP_ITEM_ID_LENGTH} of A-Z, 0-9 and _",
            )
        }
        return raw
    }

    private val ITEM_ID = Regex("[A-Z0-9_]{1,${WyrApi.Limits.MAX_SHOP_ITEM_ID_LENGTH}}")
}

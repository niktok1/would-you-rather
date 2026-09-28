package io.ntole.wyr.core.domain.shop

/** The shop (CLAUDE.md §8d, *The shop*). Implemented in `:core:data`. */
public interface ShopRepository {
    /**
     * What the shop sells, with what the session player owns and their points.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure.
     */
    public suspend fun shop(): Shop

    /**
     * Buys the theme of [themeId] for the session player, answered with the shop as it stands after it.
     * The price is the server's, taken from the player's points in the purchase's own transaction.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure:
     *   [io.ntole.wyr.core.domain.error.DomainError.ACCOUNT_REQUIRED] for a guest,
     *   [io.ntole.wyr.core.domain.error.DomainError.NOT_ENOUGH_POINTS] for too few points,
     *   [io.ntole.wyr.core.domain.error.DomainError.ALREADY_OWNED] for a theme bought already, a
     *   purchase whose answer was lost included, and
     *   [io.ntole.wyr.core.domain.error.DomainError.ITEM_NOT_FOUND] for one the shop does not sell;
     *   each takes nothing.
     */
    public suspend fun buy(themeId: String): Shop
}

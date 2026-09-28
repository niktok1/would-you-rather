package io.ntole.wyr.core.domain.shop

import io.ntole.wyr.core.domain.session.SessionRepository

/** Buys a theme, as [ShopRepository.buy], once a session exists. */
public class BuyTheme(
    private val shop: ShopRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(themeId: String): Shop {
        session.ensure()
        return shop.buy(themeId)
    }
}

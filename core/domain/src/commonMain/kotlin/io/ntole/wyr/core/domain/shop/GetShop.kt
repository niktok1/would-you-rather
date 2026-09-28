package io.ntole.wyr.core.domain.shop

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * The shop, as [ShopRepository.shop], once a session exists: what is owned is the session player's, so
 * the session is ensured first, as the game does before it reads the player's stats.
 */
public class GetShop(
    private val shop: ShopRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(): Shop {
        session.ensure()
        return shop.shop()
    }
}

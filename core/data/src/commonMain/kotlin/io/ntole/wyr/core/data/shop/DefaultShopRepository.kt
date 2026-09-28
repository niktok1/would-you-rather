package io.ntole.wyr.core.data.shop

import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.withSessionRecovery
import io.ntole.wyr.core.domain.shop.Shop
import io.ntole.wyr.core.domain.shop.ShopRepository
import io.ntole.wyr.core.network.api.ShopApi
import io.ntole.wyr.core.shop.PurchaseRequest

/**
 * The shop, through [withSessionRecovery], as a submission goes: on a dead session the retry is the
 * fresh guest's, whose read is a guest's shop and whose purchase is refused as one
 * (`ACCOUNT_REQUIRED`), and the 401 took nothing, so a purchase is never paid twice.
 */
public class DefaultShopRepository(
    private val api: ShopApi,
    private val session: DefaultSessionRepository,
) : ShopRepository {
    override suspend fun shop(): Shop = session.withSessionRecovery { api.shop() }.toDomain()

    override suspend fun buy(themeId: String): Shop =
        session.withSessionRecovery { api.buy(PurchaseRequest(itemId = themeId)) }.toDomain()
}

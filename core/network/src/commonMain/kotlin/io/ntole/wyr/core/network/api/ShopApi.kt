package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.shop.PurchaseRequest
import io.ntole.wyr.core.shop.ShopDto

/** The shop (CLAUDE.md §8d, *The shop*). Both calls require a session. */
public class ShopApi(
    private val client: HttpClient,
) {
    /** What the shop sells, with what the session player owns and their points. */
    public suspend fun shop(): ShopDto = client.get(WyrApi.Paths.SHOP).body()

    /** Buys one item for the session player, answered with the shop as it stands after it. */
    public suspend fun buy(request: PurchaseRequest): ShopDto =
        client
            .post(WyrApi.Paths.MY_PURCHASES) {
                setBody(request)
            }.body()
}

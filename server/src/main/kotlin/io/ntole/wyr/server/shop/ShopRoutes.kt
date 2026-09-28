package io.ntole.wyr.server.shop

import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.shop.PurchaseRequest
import io.ntole.wyr.server.auth.IdentityStore
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.authenticatedPlayerId
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit
import io.ntole.wyr.server.plugins.receiveOrReject

/**
 * The shop (CLAUDE.md §8d, *The shop*). Reading it and buying both need a session, since what is owned
 * is the player's, and buying needs a registered player's, as submitting does. Every theme costs
 * [themePrice], the server's `THEME_PRICE`, taken from the player's points (CLAUDE.md §8c).
 */
fun Route.shopRoutes(
    db: Db,
    themePrice: Int,
) {
    authenticate(JWT_AUTH) {
        rateLimit(RouteLimit.SHOP) {
            get(WyrApi.Paths.SHOP) {
                val playerId = call.authenticatedPlayerId()

                // As for the stats: a validly signed token can outlive its player.
                val shop =
                    db.query { ShopStore.shopOf(playerId, themePrice) }
                        ?: throw ApiFailure.unauthorized("unknown player")

                call.respond(shop)
            }
        }

        rateLimit(RouteLimit.PURCHASES) {
            post(WyrApi.Paths.MY_PURCHASES) {
                val playerId = call.authenticatedPlayerId()

                val request = call.receiveOrReject<PurchaseRequest>("purchase")

                val (itemId, shop) =
                    db.query {
                        // Only a registered player may buy, as only one may submit (CLAUDE.md §8d,
                        // *Submitting*), so a guest is refused before anything they sent is checked: one with
                        // neither a username nor a Play Games link. Plain reads: nothing unregisters a player
                        // or unlinks one. A validly signed token can outlive its player.
                        val player = PlayerStore.find(playerId) ?: throw ApiFailure.unauthorized("unknown player")
                        if (player.username == null && !IdentityStore.isLinked(playerId)) {
                            throw ApiFailure.accountRequired("buy")
                        }

                        val itemId = ShopCatalog.checkedItemId(request.itemId)
                        ShopStore.buy(playerId, itemId, price = themePrice)
                        // In the purchase's own transaction, so the answer shows the item owned and its
                        // price taken together.
                        itemId to checkNotNull(ShopStore.shopOf(playerId, themePrice)) { "the buyer vanished" }
                    }

                // For the operator's trail (CLAUDE.md §8b, *Logging*): who bought what, by id alone.
                call.application.log.info("player $playerId bought $itemId")

                call.respond(shop)
            }
        }
    }
}

package io.ntole.wyr.server.home

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.home.HomePickRequest
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.authenticatedPlayerId
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit
import io.ntole.wyr.server.plugins.receiveOrReject

/**
 * The Home screen's two Play buttons (CLAUDE.md §8d, *Home picks*). Reading the counts needs no
 * session, as the categories list does not, so it is limited per address; a tap needs one, so it is
 * limited per player, which is what bounds how fast one player can move a count.
 */
fun Route.homePickRoutes(db: Db) {
    rateLimit(RouteLimit.HOME_PICK_COUNTS) {
        get(WyrApi.Paths.HOME_PICKS) {
            call.respond(db.query { HomePickStore.counts() })
        }
    }

    authenticate(JWT_AUTH) {
        rateLimit(RouteLimit.HOME_PICKS) {
            post(WyrApi.Paths.HOME_PICKS) {
                val playerId = call.authenticatedPlayerId()
                val body = call.receiveOrReject<HomePickRequest>("home pick")

                val counts =
                    db.query {
                        // As for a vote: a validly signed token can outlive its player.
                        if (PlayerStore.find(playerId) == null) throw ApiFailure.unauthorized("unknown player")
                        HomePickStore.pick(body.side)
                    }

                call.respond(counts)
            }
        }
    }
}

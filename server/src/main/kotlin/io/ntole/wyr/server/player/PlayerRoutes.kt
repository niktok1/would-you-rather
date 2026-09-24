package io.ntole.wyr.server.player

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.authenticatedPlayerId
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit

/** "Me" is whoever the bearer token names, so the stats need a session, as the feed does. */
fun Route.playerRoutes(db: Db) {
    authenticate(JWT_AUTH) {
        rateLimit(RouteLimit.STATS) {
            get(WyrApi.Paths.ME) {
                val playerId = call.authenticatedPlayerId()

                // As for the feed and a vote: a validly signed token can outlive its player.
                val stats = db.query { StatsStore.of(playerId) } ?: throw ApiFailure.unauthorized("unknown player")

                call.respond(stats)
            }
        }
    }
}

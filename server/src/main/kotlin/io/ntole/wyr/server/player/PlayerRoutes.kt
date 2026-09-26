package io.ntole.wyr.server.player

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.authenticatedPlayerId
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit

/** "Me" is whoever the bearer token names, so the stats and deleting the account need a session. */
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

        // The player's own account, gone for good (CLAUDE.md §8a, *Deleting an account*).
        rateLimit(RouteLimit.DELETIONS) {
            post(WyrApi.Paths.ME_DELETION) {
                val playerId = call.authenticatedPlayerId()

                db.query { AccountDeletion.delete(playerId) }
                call.application.log.info("player $playerId deleted their account")

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

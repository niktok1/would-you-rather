package io.ntole.wyr.server.reaction

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.reaction.ReactionRequest
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.authenticatedPlayerId
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit
import io.ntole.wyr.server.plugins.receiveOrReject
import io.ntole.wyr.server.plugins.requireValidId

/** A reaction is the session player's, as a vote is, so it needs a session. */
fun Route.reactionRoutes(db: Db) {
    authenticate(JWT_AUTH) {
        rateLimit(RouteLimit.REACTIONS) {
            post(WyrApi.Paths.REACTIONS) {
                val playerId = call.authenticatedPlayerId()

                val body = call.receiveOrReject<ReactionRequest>("reaction request")
                requireValidId("questionId", body.questionId)

                // One transaction covers the reaction and the author's point, so they commit or fail together.
                val result =
                    db.query {
                        ReactionStore.set(playerId = playerId, questionId = body.questionId, reaction = body.reaction)
                    }

                call.respond(result)
            }
        }
    }
}

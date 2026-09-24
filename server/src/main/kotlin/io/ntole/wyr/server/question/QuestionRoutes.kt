package io.ntole.wyr.server.question

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.SkipRequest
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.authenticatedPlayerId
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.pageLimit
import io.ntole.wyr.server.plugins.rateLimit
import io.ntole.wyr.server.plugins.receiveOrReject
import io.ntole.wyr.server.plugins.requireValidId

/**
 * The feed is per player (CLAUDE.md §8d), so reading questions needs a session, as voting does, and
 * so does skipping one, which changes what the feed serves that player.
 */
fun Route.questionRoutes(db: Db) {
    authenticate(JWT_AUTH) {
        rateLimit(RouteLimit.FEED) {
            get(WyrApi.Paths.QUESTIONS) {
                val playerId = call.authenticatedPlayerId()
                val params = call.request.queryParameters

                val limit = params.pageLimit()

                // Filtering by UNKNOWN would always answer an empty batch, which the feed otherwise never
                // does while it has questions, so it is refused with every other value that is no category.
                val categories = params.categoryFilter()

                val batch =
                    db.query {
                        // As for a vote: a validly signed token can outlive its player. Serving it the feed
                        // of a player with no answers would only put the 401 off until its first vote.
                        if (PlayerStore.find(playerId) == null) throw ApiFailure.unauthorized("unknown player")
                        QuestionStore.feed(playerId, limit, categories)
                    }

                call.respond(batch)
            }
        }

        rateLimit(RouteLimit.SKIPS) {
            post(WyrApi.Paths.SKIPS) {
                val playerId = call.authenticatedPlayerId()

                val body = call.receiveOrReject<SkipRequest>("skip request")
                requireValidId("questionId", body.questionId)

                db.query { SkipStore.skip(playerId = playerId, questionId = body.questionId) }

                // A skip pays nothing and reveals nothing, so there is nothing to send back.
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

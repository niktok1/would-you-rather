package io.ntole.wyr.server.like

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.like.LikeRequest
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.authenticatedPlayerId
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.receiveOrReject
import io.ntole.wyr.server.plugins.requireValidId

/** A like is the session player's, as a vote is, so it needs a session. */
fun Route.likeRoutes(db: Db) {
    authenticate(JWT_AUTH) {
        post(WyrApi.Paths.LIKES) {
            val playerId = call.authenticatedPlayerId()

            val body = call.receiveOrReject<LikeRequest>("like request")
            requireValidId("questionId", body.questionId)

            // One transaction covers the like and the author's point, so they commit or fail together.
            val result =
                db.query { LikeStore.setLiked(playerId = playerId, questionId = body.questionId, liked = body.liked) }

            call.respond(result)
        }
    }
}

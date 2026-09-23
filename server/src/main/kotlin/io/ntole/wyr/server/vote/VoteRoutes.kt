package io.ntole.wyr.server.vote

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.authenticatedPlayerId
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.receiveOrReject

fun Route.voteRoutes(db: Db) {
    authenticate(JWT_AUTH) {
        post(WyrApi.Paths.VOTES) {
            val playerId = call.authenticatedPlayerId()

            val body = call.receiveOrReject<VoteRequest>("vote request")

            if (body.questionId.isBlank()) throw ApiFailure.validation("questionId is blank")
            if (body.attemptId.isBlank()) throw ApiFailure.validation("attemptId is blank")
            if (body.attemptId.length > WyrApi.Limits.MAX_ATTEMPT_ID_LENGTH) {
                throw ApiFailure.validation("attemptId is over ${WyrApi.Limits.MAX_ATTEMPT_ID_LENGTH} characters")
            }

            // One transaction covers the vote, the tally, and the point award, so they commit or fail
            // together. The points do not depend on the tally (§8c). It locks only this player's vote,
            // not the tally: at READ COMMITTED the tally counts what other players had committed when
            // it ran, which can include votes committed after this request began, and misses votes
            // still in flight. The stored votes, which the next read counts, are exact.
            val result =
                db.query {
                    VoteStore.cast(
                        playerId = playerId,
                        questionId = body.questionId,
                        choice = body.choice,
                        attemptId = body.attemptId,
                    )
                }

            call.respond(result)
        }
    }
}

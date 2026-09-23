package io.ntole.wyr.server.vote

import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.receiveOrReject

fun Route.voteRoutes(db: Db) {
    authenticate(JWT_AUTH) {
        post(WyrApi.Paths.VOTES) {
            val playerId =
                call
                    .principal<JWTPrincipal>()
                    ?.payload
                    ?.getClaim(TokenService.CLAIM_PLAYER_ID)
                    ?.asString()
                    ?: throw ApiFailure.unauthorized("token carries no player id")

            val body = call.receiveOrReject<VoteRequest>("vote request")

            if (body.questionId.isBlank()) throw ApiFailure.validation("questionId is blank")

            // One transaction covers insert, tally, and score, so they commit or fail together
            // and the points are computed from exactly the tally returned. It is not a lock: under
            // REPEATABLE_READ a concurrent vote by another player on the same question may not be
            // visible yet, so the tally can lag by votes still in flight. The stored votes, which
            // the next read counts, are exact.
            val result =
                db.query {
                    VoteStore.cast(
                        playerId = playerId,
                        questionId = body.questionId,
                        choice = body.choice,
                    )
                }

            call.respond(result)
        }
    }
}

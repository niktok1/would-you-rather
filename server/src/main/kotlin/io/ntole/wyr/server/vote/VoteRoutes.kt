package io.ntole.wyr.server.vote

import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.ApiFailure

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

            val body =
                runCatching { call.receive<VoteRequest>() }.getOrNull()
                    ?: throw ApiFailure.validation("malformed vote request")

            if (body.questionId.isBlank()) throw ApiFailure.validation("questionId is blank")

            // One transaction covers insert, tally, and score, so a concurrent vote cannot land
            // between counting and awarding and make the two disagree.
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

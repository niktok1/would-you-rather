package io.ntole.wyr.server.question

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.authenticatedPlayerId
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit
import io.ntole.wyr.server.plugins.receiveOrReject

/**
 * A player's own questions (CLAUDE.md §8d). Submitting one and listing them both need a session,
 * since the author is whoever the bearer token names.
 */
fun Route.submissionRoutes(db: Db) {
    authenticate(JWT_AUTH) {
        rateLimit(RouteLimit.MY_SUBMISSIONS) {
            get(WyrApi.Paths.MY_QUESTIONS) {
                val authorId = call.authenticatedPlayerId()

                val submissions =
                    db.query {
                        // As for the feed: a validly signed token can outlive its player, and an empty list
                        // would only hide that the session is dead.
                        if (PlayerStore.find(authorId) == null) throw ApiFailure.unauthorized("unknown player")
                        SubmissionStore.byAuthor(authorId)
                    }

                call.respond(SubmissionListDto(submissions))
            }
        }

        rateLimit(RouteLimit.SUBMISSIONS) {
            post(WyrApi.Paths.QUESTIONS) {
                val authorId = call.authenticatedPlayerId()

                // Checked before the transaction, as a vote's ids are: a refusal needs no database.
                val submission = checkedSubmission(call.receiveOrReject<SubmitQuestionRequest>("submission"))

                val stored = db.query { SubmissionStore.submit(authorId, submission) }

                call.respond(HttpStatusCode.Created, stored)
            }
        }
    }
}

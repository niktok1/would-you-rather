package io.ntole.wyr.server.question

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
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
import io.ntole.wyr.server.category.CategoryStore
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit
import io.ntole.wyr.server.plugins.receiveOrReject

/**
 * A player's own questions (CLAUDE.md §8d). Submitting one and listing them both need a session,
 * since the author is whoever the bearer token names, and submitting needs a registered player's. A
 * guest still lists whatever it submitted before that rule.
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

                val request = call.receiveOrReject<SubmitQuestionRequest>("submission")

                val stored =
                    db.query {
                        // Only a registered player may submit (CLAUDE.md §8d, *Submitting*), so a guest is
                        // refused before anything they sent is checked. A plain read: nothing unregisters a
                        // player, and a guest registering meanwhile sent this as a guest. As for the list, a
                        // validly signed token can outlive its player.
                        val author = PlayerStore.find(authorId) ?: throw ApiFailure.unauthorized("unknown player")
                        if (author.username == null) throw ApiFailure.accountRequired()
                        // Told before what they typed is checked, as a guest is: nothing they could type
                        // would pass. A plain read, so the submission checks again under the author's lock.
                        if (author.submissionsBlocked) throw ApiFailure.submissionsBlocked()

                        // An id no category has is a malformed request, which comes before any rule the
                        // player can break by typing (checkedSubmission).
                        val categories = CategoryStore.checked(request.categories)
                        SubmissionStore.submit(authorId, checkedSubmission(request.copy(categories = categories)))
                    }

                // For the moderator's and the operator's trail: which question, by whom. Never its text,
                // which is the player's own words.
                call.application.log.info("submission ${stored.id} stored, by player $authorId")

                call.respond(HttpStatusCode.Created, stored)
            }
        }
    }
}

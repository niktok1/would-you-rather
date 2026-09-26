package io.ntole.wyr.server.report

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.report.HideAuthorRequest
import io.ntole.wyr.core.report.HideQuestionRequest
import io.ntole.wyr.core.report.ReportReason
import io.ntole.wyr.core.report.ReportRequest
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.authenticatedPlayerId
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit
import io.ntole.wyr.server.plugins.receiveOrReject
import io.ntole.wyr.server.plugins.requireValidId

/**
 * Reporting and hiding questions (CLAUDE.md §8d, *Reports*). Each is the session player's, as a vote
 * is, so each needs a session, and each answers 204: there is nothing to show for it but the question
 * gone from the feed.
 */
fun Route.reportRoutes(db: Db) {
    authenticate(JWT_AUTH) {
        rateLimit(RouteLimit.REPORTS) {
            post(WyrApi.Paths.REPORTS) {
                val playerId = call.authenticatedPlayerId()

                val body = call.receiveOrReject<ReportRequest>("report")
                requireValidId("questionId", body.questionId)
                // UNKNOWN is what a reason this build has no name for decodes as, and what a report that
                // gives none defaults to: neither tells the moderator anything.
                if (body.reason == ReportReason.UNKNOWN) throw ApiFailure.validation("a report needs a reason")

                db.query { ReportStore.report(playerId, body.questionId, body.reason) }

                call.respond(HttpStatusCode.NoContent)
            }
        }

        rateLimit(RouteLimit.HIDES) {
            post(WyrApi.Paths.HIDDEN_QUESTIONS) {
                val playerId = call.authenticatedPlayerId()

                val body = call.receiveOrReject<HideQuestionRequest>("hidden question")
                requireValidId("questionId", body.questionId)

                db.query { ReportStore.hideQuestion(playerId, body.questionId) }

                call.respond(HttpStatusCode.NoContent)
            }

            post(WyrApi.Paths.HIDDEN_AUTHORS) {
                val playerId = call.authenticatedPlayerId()

                val body = call.receiveOrReject<HideAuthorRequest>("hidden author")
                requireValidId("questionId", body.questionId)

                db.query { ReportStore.hideAuthorOf(playerId, body.questionId) }

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

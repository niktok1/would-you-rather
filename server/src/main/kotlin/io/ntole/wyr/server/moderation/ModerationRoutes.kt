package io.ntole.wyr.server.moderation

import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.pageLimit

/**
 * The moderator's routes (CLAUDE.md §8d, *Moderation*). The moderator is whoever holds the server's
 * admin token, not a player: each route checks [WyrApi.Headers.ADMIN_TOKEN] first ([requireAdmin])
 * and none reads a bearer token, so none sits inside `authenticate` and none can answer 401.
 *
 * With no [adminToken] configured none is registered at all, so each path is 404 exactly as a path
 * the server never had, and nothing about the request is looked at.
 */
fun Route.moderationRoutes(
    db: Db,
    adminToken: AdminToken?,
) {
    if (adminToken == null) return

    get(WyrApi.Paths.ADMIN_SUBMISSIONS) {
        call.requireAdmin(adminToken)
        val params = call.request.queryParameters

        val status = params.status()
        val limit = params.pageLimit()

        call.respond(SubmissionListDto(db.query { ModerationStore.queue(status, limit) }))
    }
}

/**
 * The status the queue is asked for, [QuestionStatus.PENDING] when none is. [QuestionStatus.UNKNOWN]
 * is the client's decoding fallback and never stored, so listing it would always answer an empty
 * list, and it is refused as a status that is not a status at all is, as for the feed's category.
 * So is a second value, rather than one of the two picked.
 */
private fun Parameters.status(): QuestionStatus {
    val named = getAll(WyrApi.Query.STATUS).orEmpty()
    if (named.size > 1) throw ApiFailure.validation("one status at a time: $named")
    val raw = named.singleOrNull() ?: return QuestionStatus.PENDING
    return QuestionStatus.entries.firstOrNull { it.name == raw && it != QuestionStatus.UNKNOWN }
        ?: throw ApiFailure.validation("unknown status: $raw")
}

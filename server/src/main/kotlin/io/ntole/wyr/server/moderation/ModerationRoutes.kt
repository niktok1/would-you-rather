package io.ntole.wyr.server.moderation

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.author.BlockAuthorRequest
import io.ntole.wyr.core.author.UnblockAuthorRequest
import io.ntole.wyr.core.category.CreateCategoryRequest
import io.ntole.wyr.core.category.RenameCategoryRequest
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.question.RestoreQuestionRequest
import io.ntole.wyr.core.question.RetireQuestionRequest
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.core.report.DismissReportsRequest
import io.ntole.wyr.server.category.CategoryStore
import io.ntole.wyr.server.category.checkedCreation
import io.ntole.wyr.server.category.checkedRenaming
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.pageLimit
import io.ntole.wyr.server.plugins.rateLimit
import io.ntole.wyr.server.plugins.receiveOrReject
import io.ntole.wyr.server.plugins.requireValidId
import io.ntole.wyr.server.push.DecisionNotifier
import io.ntole.wyr.server.question.categoryFilter

/**
 * The moderator's routes (CLAUDE.md §8d, *Moderation*). The moderator is whoever holds the server's
 * admin token, not a player: each route checks [WyrApi.Headers.ADMIN_TOKEN] first ([requireAdmin])
 * and none reads a bearer token, so none sits inside `authenticate` and none can answer 401.
 *
 * With no [adminToken] configured none is registered at all, so each path is 404 exactly as a path
 * the server never had, and nothing about the request is looked at.
 *
 * A decision is pushed to its author through [notifier] once it has committed (CLAUDE.md §8a, *Push
 * tokens*), or to nobody when pushes are off, the notifier null.
 */
fun Route.moderationRoutes(
    db: Db,
    adminToken: AdminToken?,
    notifier: DecisionNotifier?,
) {
    if (adminToken == null) return

    // Every admin request spends from the admin budget, and one without the right token from the
    // failed-token budget as well, which is what bounds guessing the token. The failures are asked
    // first, so a caller who has spent theirs is refused before touching the moderator's own budget.
    rateLimit(RouteLimit.ADMIN_TOKEN_FAILURES) {
        rateLimit(RouteLimit.ADMIN) {
            get(WyrApi.Paths.ADMIN_SUBMISSIONS) {
                call.requireAdmin(adminToken)
                val params = call.request.queryParameters

                val status = params.status()
                val limit = params.pageLimit()

                call.respond(SubmissionListDto(db.query { ModerationStore.queue(status, limit) }))
            }

            post(WyrApi.Paths.ADMIN_APPROVALS) {
                call.requireAdmin(adminToken)

                // Checked before the transaction, as a submission is: a refusal needs no database.
                val approval = checkedApproval(call.receiveOrReject<ApproveSubmissionRequest>("approval"))

                val decided =
                    db.query {
                        // Before the decision, so an id no category has is refused whatever the question.
                        ModerationStore.approve(approval.questionId, CategoryStore.checked(approval.categories))
                    }
                call.logAdmin("approved question", decided.id)

                // After the commit, and not waited for: a push never holds up or fails a decision.
                notifier?.submissionDecided(decided)
                call.respond(decided)
            }

            post(WyrApi.Paths.ADMIN_REJECTIONS) {
                call.requireAdmin(adminToken)

                val rejection = checkedRejection(call.receiveOrReject<RejectSubmissionRequest>("rejection"))

                val decided = db.query { ModerationStore.reject(rejection.questionId, rejection.reason) }
                call.logAdmin("rejected question", decided.id)

                notifier?.submissionDecided(decided)
                call.respond(decided)
            }

            get(WyrApi.Paths.ADMIN_QUESTIONS) {
                call.requireAdmin(adminToken)
                val params = call.request.queryParameters

                val statuses = params.statuses()
                val categories = params.categoryFilter()
                val after = params.cursor()
                val limit = params.pageLimit()

                call.respond(
                    db.query {
                        val filter = CategoryStore.checked(categories).toSet()
                        ModerationStore.questions(statuses, filter, after, limit)
                    },
                )
            }

            post(WyrApi.Paths.ADMIN_RETIREMENTS) {
                call.requireAdmin(adminToken)

                val retirement = call.receiveOrReject<RetireQuestionRequest>("retirement")
                requireValidId("questionId", retirement.questionId)

                val retired = db.query { ModerationStore.retire(retirement.questionId) }
                call.logAdmin("retired question", retired.id)

                call.respond(retired)
            }

            post(WyrApi.Paths.ADMIN_RESTORATIONS) {
                call.requireAdmin(adminToken)

                val restoration = call.receiveOrReject<RestoreQuestionRequest>("restoration")
                requireValidId("questionId", restoration.questionId)

                val restored = db.query { ModerationStore.restore(restoration.questionId) }
                call.logAdmin("restored question", restored.id)

                call.respond(restored)
            }

            post(WyrApi.Paths.ADMIN_CATEGORIES) {
                call.requireAdmin(adminToken)

                // Checked before the transaction: a refusal needs no database.
                val category = checkedCreation(call.receiveOrReject<CreateCategoryRequest>("category"))

                val created = db.query { CategoryStore.create(category) }
                call.logAdmin("added category", created.id)

                call.respond(HttpStatusCode.Created, created)
            }

            post(WyrApi.Paths.ADMIN_CATEGORY_RENAMES) {
                call.requireAdmin(adminToken)

                val category = checkedRenaming(call.receiveOrReject<RenameCategoryRequest>("category rename"))

                val renamed = db.query { CategoryStore.rename(category) }
                call.logAdmin("renamed category", renamed.id)

                call.respond(renamed)
            }

            get(WyrApi.Paths.ADMIN_REPORTS) {
                call.requireAdmin(adminToken)

                val limit = call.request.queryParameters.pageLimit()

                call.respond(db.query { ModerationStore.reports(limit) })
            }

            post(WyrApi.Paths.ADMIN_REPORT_DISMISSALS) {
                call.requireAdmin(adminToken)

                val dismissal = call.receiveOrReject<DismissReportsRequest>("report dismissal")
                requireValidId("questionId", dismissal.questionId)

                val dismissed = db.query { ModerationStore.dismissReports(dismissal.questionId) }
                call.logAdmin("dismissed $dismissed reports of question", dismissal.questionId)

                call.respond(HttpStatusCode.NoContent)
            }

            post(WyrApi.Paths.ADMIN_AUTHOR_BLOCKS) {
                call.requireAdmin(adminToken)

                // Checked before the transaction, as a rejection is: a refusal needs no database.
                val block = checkedBlock(call.receiveOrReject<BlockAuthorRequest>("author block"))

                val blocked = db.query { ModerationStore.blockAuthor(block.authorId, block.reason) }
                call.logAdmin("blocked author, rejecting ${blocked.rejectedSubmissions} pending,", block.authorId)

                call.respond(blocked)
            }

            post(WyrApi.Paths.ADMIN_AUTHOR_UNBLOCKS) {
                call.requireAdmin(adminToken)

                val unblock = call.receiveOrReject<UnblockAuthorRequest>("author unblock")
                requireValidId("authorId", unblock.authorId)

                val unblocked = db.query { ModerationStore.unblockAuthor(unblock.authorId) }
                call.logAdmin("unblocked author", unblocked.authorId)

                call.respond(unblocked)
            }
        }
    }
}

/**
 * One INFO line for an admin action that went through, one that changed nothing included, naming
 * [what] it did and to [id]: the operator's trail of what the moderator did. Never a token, nor a
 * reason or a name the moderator typed: the log is no place for either.
 */
private fun ApplicationCall.logAdmin(
    what: String,
    id: String,
) = application.log.info("admin $what $id")

/**
 * The status the queue is asked for, [QuestionStatus.PENDING] when none is, refused as [statusNamed]
 * refuses one. So is a second value, rather than one of the two picked.
 */
private fun Parameters.status(): QuestionStatus {
    val named = getAll(WyrApi.Query.STATUS).orEmpty()
    if (named.size > 1) throw ApiFailure.validation("one status at a time: $named")
    val raw = named.singleOrNull() ?: return QuestionStatus.PENDING
    return statusNamed(raw)
}

/** The statuses the question list is asked for, one per repeat of the parameter, none for every one. */
private fun Parameters.statuses(): Set<QuestionStatus> =
    getAll(WyrApi.Query.STATUS).orEmpty().map(::statusNamed).toSet()

/**
 * The status [raw] names. [QuestionStatus.UNKNOWN] is the client's decoding fallback and never stored,
 * so listing it would always answer an empty list, and it is refused as a status that is not a status
 * at all is, as for the feed's category.
 */
private fun statusNamed(raw: String): QuestionStatus =
    QuestionStatus.entries.firstOrNull { it.name == raw && it != QuestionStatus.UNKNOWN }
        ?: throw ApiFailure.validation("unknown status: $raw")

/** Where the page asked for starts, if not at the first question: given at most once, as it was sent. */
private fun Parameters.cursor(): QuestionCursor? {
    val sent = getAll(WyrApi.Query.CURSOR).orEmpty()
    if (sent.size > 1) throw ApiFailure.validation("one cursor at a time")
    return sent.singleOrNull()?.let(QuestionCursor::parse)
}

package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmissionListDto

/**
 * The moderator's routes (CLAUDE.md §8d, *Moderation*). Each call carries [adminToken] in
 * [WyrApi.Headers.ADMIN_TOKEN], and only that call: the token is the caller's to hold, in memory,
 * and is never set on the client, stored, or put anywhere the HTTP trace reads (it records no
 * headers).
 *
 * The player's session is left alone. The Auth plugin still attaches the bearer token when there is
 * one, as it does to every request, and the admin routes ignore it. They answer a wrong token 403,
 * never 401, and the plugin refreshes only on a 401, so no moderator's mistake can rotate or replace
 * the player's session.
 */
public class ModerationApi(
    private val client: HttpClient,
) {
    /**
     * The submissions waiting for a decision, oldest first, at most [limit] of them: so the head is
     * the next to decide, and asking again after deciding it gets the rest.
     */
    public suspend fun pending(
        adminToken: String,
        limit: Int = WyrApi.Limits.DEFAULT_PAGE_SIZE,
    ): SubmissionListDto =
        client
            .get(WyrApi.Paths.ADMIN_SUBMISSIONS) {
                admin(adminToken)
                // Named although it is the server's default, so the trace says which list was asked for.
                parameter(WyrApi.Query.STATUS, QuestionStatus.PENDING.name)
                parameter(WyrApi.Query.LIMIT, limit)
            }.body()

    /** Approves a pending submission, answered with it as its author now sees it. */
    public suspend fun approve(
        adminToken: String,
        request: ApproveSubmissionRequest,
    ): SubmissionDto =
        client
            .post(WyrApi.Paths.ADMIN_APPROVALS) {
                admin(adminToken)
                setBody(request)
            }.body()

    /** Rejects a pending submission, answered with it as its author now sees it, reason and all. */
    public suspend fun reject(
        adminToken: String,
        request: RejectSubmissionRequest,
    ): SubmissionDto =
        client
            .post(WyrApi.Paths.ADMIN_REJECTIONS) {
                admin(adminToken)
                setBody(request)
            }.body()

    private fun HttpRequestBuilder.admin(adminToken: String) {
        header(WyrApi.Headers.ADMIN_TOKEN, adminToken)
    }
}

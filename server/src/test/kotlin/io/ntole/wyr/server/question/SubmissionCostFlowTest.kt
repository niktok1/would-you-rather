package io.ntole.wyr.server.question

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.mintGuest
import io.ntole.wyr.server.runTestServer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What submitting costs is the server's setting, `SUBMISSION_COST` (CLAUDE.md §8c): charged, kept on
 * the question, and named in `GET /v1/me`, so the game says what this server charges.
 */
class SubmissionCostFlowTest {
    @Test
    fun `the cost the server is set to is the one it charges and names`() =
        runTestServer("submission-cost-set", configure = { it.copy(submissionCost = COST) }) { client, _ ->
            val author = client.registered("costly")
            assertEquals(COST, client.stats(author).submissionCost)

            repeat(COST - 1) { client.answer(author) }
            val refused = client.submit(author)
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals(ErrorCode.NOT_ENOUGH_POINTS, refused.body<ErrorDto>().code)

            client.answer(author)
            assertEquals(HttpStatusCode.Created, client.submit(author).status)
            val stats = client.stats(author)
            assertEquals(0, stats.totalPoints, "the whole cost taken")
            assertEquals(COST, stats.pointsSpent)
        }

    @Test
    fun `a server with no cost set charges 1 and names it`() =
        runTestServer("submission-cost-unset") { client, _ ->
            val author = client.registered("cheap")
            assertEquals(1, client.stats(author).submissionCost)

            client.answer(author)
            assertEquals(HttpStatusCode.Created, client.submit(author).status)
            assertEquals(1, client.stats(author).pointsSpent)
        }

    @Test
    fun `a cost of 0 lets a player with no points submit`() =
        runTestServer("submission-cost-free", configure = { it.copy(submissionCost = 0) }) { client, _ ->
            val author = client.registered("free")

            assertEquals(HttpStatusCode.Created, client.submit(author).status)
            assertEquals(0, client.stats(author).pointsSpent)
        }

    private suspend fun HttpClient.registered(username: String): SessionDto {
        val guest = mintGuest()
        val registered =
            post(WyrApi.Paths.AUTH_REGISTER) {
                bearerAuth(guest.accessToken)
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest(username, "a password"))
            }
        assertEquals(HttpStatusCode.OK, registered.status)
        return guest
    }

    /** One more answer, a point, whichever side: a re-answer pays every time (CLAUDE.md §8d). */
    private suspend fun HttpClient.answer(session: SessionDto) {
        val answered =
            post(WyrApi.Paths.VOTES) {
                bearerAuth(session.accessToken)
                contentType(ContentType.Application.Json)
                setBody(VoteRequest("seed-1", OptionSide.A, UUID.randomUUID().toString()))
            }
        assertEquals(HttpStatusCode.OK, answered.status)
    }

    private suspend fun HttpClient.submit(session: SessionDto): HttpResponse =
        post(WyrApi.Paths.QUESTIONS) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(SubmitQuestionRequest("Лети", "Плива", listOf("FOOD")))
        }

    private suspend fun HttpClient.stats(session: SessionDto): PlayerStatsDto =
        get(WyrApi.Paths.ME) { bearerAuth(session.accessToken) }.body()

    private companion object {
        const val COST = 3
    }
}

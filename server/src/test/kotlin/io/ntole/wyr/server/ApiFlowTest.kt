package io.ntole.wyr.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.vote.Scoring
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the vertical slice: zero-click session, question fetch, vote, reveal.
 *
 * Each test gets its own in-memory database, keyed by name, so vote counts from one test cannot
 * bleed into another's tally assertions.
 */
class ApiFlowTest {
    @Test
    fun `guest session, question fetch, and vote all work with no player interaction`() =
        runServer("happy-path") { client ->
            val session: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()
            assertTrue(session.playerId.isNotBlank())
            assertTrue(session.accessToken.isNotBlank())
            assertTrue(session.refreshToken.isNotBlank())

            val page: QuestionPageDto = client.get(WyrApi.Paths.QUESTIONS).body()
            assertTrue(page.questions.isNotEmpty(), "seed questions should be served")

            val question = page.questions.first()
            val result: VoteResultDto =
                client
                    .post(WyrApi.Paths.VOTES) {
                        bearerAuth(session.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(VoteRequest(questionId = question.id, choice = OptionSide.A))
                    }.body()

            assertEquals(question.id, result.questionId)
            assertEquals(OptionSide.A, result.yourChoice)
            // The voter's own vote is included in the tally they are shown.
            assertEquals(1L, result.tally.votesA)
            assertEquals(0L, result.tally.votesB)
            // First vote is trivially the majority, so it earns base + bonus and opens a streak.
            assertEquals(1, result.streak)
            assertEquals(result.pointsAwarded, result.totalPoints)
            assertTrue(result.pointsAwarded > Scoring.BASE_POINTS)
        }

    @Test
    fun `voting twice on the same question is rejected`() =
        runServer("double-vote") { client ->
            val session: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()
            val question =
                client
                    .get(WyrApi.Paths.QUESTIONS)
                    .body<QuestionPageDto>()
                    .questions
                    .first()

            client.post(WyrApi.Paths.VOTES) {
                bearerAuth(session.accessToken)
                contentType(ContentType.Application.Json)
                setBody(VoteRequest(question.id, OptionSide.A))
            }

            val second =
                client.post(WyrApi.Paths.VOTES) {
                    bearerAuth(session.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(VoteRequest(question.id, OptionSide.B))
                }

            assertEquals(HttpStatusCode.Conflict, second.status)
            assertEquals(ErrorCode.ALREADY_VOTED, second.body<ErrorDto>().code)
        }

    @Test
    fun `voting without a token is unauthorized and reports why`() =
        runServer("no-token") { client ->
            val question =
                client
                    .get(WyrApi.Paths.QUESTIONS)
                    .body<QuestionPageDto>()
                    .questions
                    .first()

            val response =
                client.post(WyrApi.Paths.VOTES) {
                    contentType(ContentType.Application.Json)
                    setBody(VoteRequest(question.id, OptionSide.A))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `refresh rotates the token and the old one stops working`() =
        runServer("refresh-rotation") { client ->
            val original: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

            val refreshed: SessionDto =
                client
                    .post(WyrApi.Paths.AUTH_REFRESH) {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(original.refreshToken))
                    }.body()

            assertEquals(original.playerId, refreshed.playerId, "refresh must not change identity")
            assertNotEquals(original.refreshToken, refreshed.refreshToken)

            // Replaying the consumed token must fail — that is the point of rotating it.
            val replay =
                client.post(WyrApi.Paths.AUTH_REFRESH) {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(original.refreshToken))
                }
            assertEquals(HttpStatusCode.Unauthorized, replay.status)
            assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, replay.body<ErrorDto>().code)
        }

    @Test
    fun `an unknown refresh token is rejected`() =
        runServer("bad-refresh") { client ->
            val response =
                client.post(WyrApi.Paths.AUTH_REFRESH) {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest("not-a-real-token"))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, response.body<ErrorDto>().code)
        }

    @Test
    fun `voting on a question that does not exist is a not-found, not a crash`() =
        runServer("missing-question") { client ->
            val session: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

            val response =
                client.post(WyrApi.Paths.VOTES) {
                    bearerAuth(session.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(VoteRequest("no-such-question", OptionSide.A))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(ErrorCode.QUESTION_NOT_FOUND, response.body<ErrorDto>().code)
        }

    @Test
    fun `a malformed cursor is a validation error rather than a 500`() =
        runServer("bad-cursor") { client ->
            val response = client.get("${WyrApi.Paths.QUESTIONS}?${WyrApi.Query.CURSOR}=abc")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code)
        }

    @Test
    fun `paging walks the catalogue and stops`() =
        runServer("paging") { client ->
            val first: QuestionPageDto =
                client.get("${WyrApi.Paths.QUESTIONS}?${WyrApi.Query.LIMIT}=5").body()
            assertEquals(5, first.questions.size)
            assertTrue(first.nextCursor != null, "a full page should offer a cursor")

            val second: QuestionPageDto =
                client
                    .get(
                        "${WyrApi.Paths.QUESTIONS}?${WyrApi.Query.LIMIT}=5" +
                            "&${WyrApi.Query.CURSOR}=${first.nextCursor}",
                    ).body()

            val firstIds = first.questions.map { it.id }.toSet()
            assertTrue(
                second.questions.none { it.id in firstIds },
                "cursor paging must not repeat rows",
            )
        }

    private fun runServer(
        databaseName: String,
        block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit,
    ) = testApplication {
        // H2 isolates tests by database name; a shared external database has to be wiped instead.
        val external = ExternalTestDatabase.fromEnvironment()
        external?.dropAppTables()

        val config =
            ServerConfig(
                port = 0,
                jdbcUrl = external?.jdbcUrl ?: "jdbc:h2:mem:wyr-test-$databaseName;DB_CLOSE_DELAY=-1",
                dbUser = external?.user,
                dbPassword = external?.password,
                jwtSecret = "test-secret",
                jwtIssuer = "wyr-test",
                jwtAudience = "wyr-test-client",
                accessTokenTtlSeconds = 300,
                refreshTokenTtlSeconds = 3_600,
                allowedWebOrigins = emptyList(),
            )

        application { wyrModule(config) }

        val client =
            createClient {
                // Left at the default (false) so a test can assert on a failure status directly.
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }

        block(client)
    }
}

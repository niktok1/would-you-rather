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
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.vote.Scoring
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
            // A first answer earns the flat point, and that point is the whole running total.
            assertEquals(Scoring.POINTS_PER_ANSWER, result.pointsAwarded)
            assertEquals(Scoring.POINTS_PER_ANSWER, result.totalPoints)
        }

    @Test
    fun `every answer pays one point whichever side it picks, and the total accumulates`() =
        runServer("flat-scoring") { client ->
            val (opened, contested) = client.get(WyrApi.Paths.QUESTIONS).body<QuestionPageDto>().questions
            val player: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

            // A tally holds one vote per player, so a real minority takes two other players on
            // the majority side first. Their majority picks pay the same flat point.
            repeat(times = 2) {
                val other: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()
                assertEquals(Scoring.POINTS_PER_ANSWER, client.vote(other, contested.id, OptionSide.A).pointsAwarded)
            }

            val majority = client.vote(player, opened.id, OptionSide.A)
            val minority = client.vote(player, contested.id, OptionSide.B)
            assertEquals(VoteTallyDto(votesA = 1, votesB = 0), majority.tally)
            assertEquals(VoteTallyDto(votesA = 2, votesB = 1), minority.tally)

            assertEquals(Scoring.POINTS_PER_ANSWER, majority.pointsAwarded)
            assertEquals(Scoring.POINTS_PER_ANSWER, minority.pointsAwarded)
            assertEquals(2 * Scoring.POINTS_PER_ANSWER, minority.totalPoints)
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
    fun `a validly signed token for a player that does not exist is unauthorized, not a duplicate vote`() =
        runServer("ghost-player") { client ->
            val question =
                client
                    .get(WyrApi.Paths.QUESTIONS)
                    .body<QuestionPageDto>()
                    .questions
                    .first()

            // The helper must sign exactly as the server does, or the 401 below would only prove
            // a bad signature. A real player's token from it has to be accepted first.
            val real: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()
            val accepted =
                client.post(WyrApi.Paths.VOTES) {
                    bearerAuth(signAccessToken(real.playerId))
                    contentType(ContentType.Application.Json)
                    setBody(VoteRequest(question.id, OptionSide.A))
                }
            assertEquals(HttpStatusCode.OK, accepted.status)

            // What a client holds after an H2 dev server restarts: a token that still verifies
            // against the constant dev secret, for a player the fresh database never had.
            val response =
                client.post(WyrApi.Paths.VOTES) {
                    bearerAuth(signAccessToken("no-such-player"))
                    contentType(ContentType.Application.Json)
                    setBody(VoteRequest(question.id, OptionSide.A))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a token signed with another secret is unauthorized`() =
        runServer("wrong-secret") { client ->
            val session: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

            val response =
                client.post(WyrApi.Paths.VOTES) {
                    bearerAuth(signAccessToken(session.playerId, secret = "not-the-server-secret"))
                    contentType(ContentType.Application.Json)
                    setBody(VoteRequest("seed-1", OptionSide.A))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a vote body the server cannot use is a validation error`() =
        runServer("malformed-vote") { client ->
            val session: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

            suspend fun assertRejected(
                case: String,
                body: String,
                type: ContentType = ContentType.Application.Json,
            ) {
                val response =
                    client.post(WyrApi.Paths.VOTES) {
                        bearerAuth(session.accessToken)
                        contentType(type)
                        setBody(body)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }

            assertRejected("malformed json", "{not json")
            assertRejected("unknown side", """{"questionId":"seed-1","choice":"C"}""")
            assertRejected("blank questionId", """{"questionId":"  ","choice":"A"}""")
            assertRejected("not sent as json", """{"questionId":"seed-1","choice":"A"}""", ContentType.Text.Plain)
        }

    @Test
    fun `a refresh body that does not parse is a validation error`() =
        runServer("malformed-refresh") { client ->
            val response =
                client.post(WyrApi.Paths.AUTH_REFRESH) {
                    contentType(ContentType.Application.Json)
                    setBody("{not json")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code)
        }

    @Test
    fun `the UNKNOWN category is rejected rather than served as an empty catalogue`() =
        runServer("unknown-category") { client ->
            val response =
                client.get("${WyrApi.Paths.QUESTIONS}?${WyrApi.Query.CATEGORY}=${QuestionCategory.UNKNOWN.name}")

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

            // Follow the cursor to the end, within a bound: the walk has to finish on a page that
            // offers no cursor rather than hand out cursors forever.
            var last = second
            repeat(times = 20) {
                val cursor = last.nextCursor ?: return@repeat
                last =
                    client
                        .get("${WyrApi.Paths.QUESTIONS}?${WyrApi.Query.LIMIT}=5&${WyrApi.Query.CURSOR}=$cursor")
                        .body()
            }
            assertNull(last.nextCursor, "the final page must offer no cursor")
        }

    private fun runServer(
        databaseName: String,
        block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit,
    ) = testApplication {
        val database = testDatabaseFor(databaseName)

        val config =
            ServerConfig(
                port = 0,
                jdbcUrl = database.jdbcUrl,
                dbUser = database.user,
                dbPassword = database.password,
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

    private suspend fun io.ktor.client.HttpClient.vote(
        session: SessionDto,
        questionId: String,
        choice: OptionSide,
    ): VoteResultDto =
        post(WyrApi.Paths.VOTES) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(VoteRequest(questionId, choice))
        }.body()

    /**
     * Signs an access token the way the server under [runServer] does, for a player id the server
     * never issued one to. The issuer, audience, and default [secret] must match [runServer]'s
     * config; the ghost-player test checks that they still do.
     */
    private fun signAccessToken(
        playerId: String,
        secret: String = "test-secret",
    ): String {
        val env =
            mapOf(
                "JWT_SECRET" to secret,
                "JWT_ISSUER" to "wyr-test",
                "JWT_AUDIENCE" to "wyr-test-client",
            )
        return TokenService(ServerConfig.fromEnvironment(env::get)).issueAccessToken(playerId)
    }
}

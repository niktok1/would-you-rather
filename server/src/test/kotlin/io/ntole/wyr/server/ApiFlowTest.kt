package io.ntole.wyr.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import io.ntole.wyr.core.question.QuestionDto
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the vertical slice: zero-click session, the question feed, vote, reveal.
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

            val batch = client.batch(session)
            assertTrue(batch.isNotEmpty(), "seed questions should be served")

            val question = batch.first()
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
            val player = client.guest()
            val (opened, contested) = client.batch(player)

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
            val session = client.guest()
            val question = client.batch(session).first()

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
            val question = client.batch(client.guest()).first()

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
    fun `a validly signed token for a player that does not exist is unauthorized, not a duplicate vote`() =
        runServer("ghost-player") { client ->
            val real = client.guest()
            val question = client.batch(real).first()

            // The helper must sign exactly as the server does, or the 401 below would only prove
            // a bad signature. A real player's token from it has to be accepted first.
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
    fun `the UNKNOWN category is rejected rather than served as an empty batch`() =
        runServer("unknown-category") { client ->
            val response = client.feed(client.guest(), "?${WyrApi.Query.CATEGORY}=${QuestionCategory.UNKNOWN.name}")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code)
        }

    @Test
    fun `the question feed needs a session`() =
        runServer("feed-no-token") { client ->
            val response = client.get(WyrApi.Paths.QUESTIONS)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a validly signed token for a player that does not exist gets no feed`() =
        runServer("feed-ghost-player") { client ->
            // As for the ghost-player vote, the helper's token for a real player has to pass first.
            val real = client.guest()
            val accepted = client.get(WyrApi.Paths.QUESTIONS) { bearerAuth(signAccessToken(real.playerId)) }
            assertEquals(HttpStatusCode.OK, accepted.status)

            val response = client.get(WyrApi.Paths.QUESTIONS) { bearerAuth(signAccessToken("no-such-player")) }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a fresh player is served every question once in an order of its own`() =
        runServer("feed-fresh") { client ->
            val first = client.guest()
            val second = client.guest()

            val pool = client.wholePool(first)
            assertEquals(pool.size, pool.ids().toSet().size, "a batch must not repeat a question")
            assertTrue(pool.none { it.answeredBefore }, "nothing is answered yet")

            val other = client.wholePool(second)
            assertEquals(pool.ids().toSet(), other.ids().toSet())
            // The order is random per request, so this can fail by pure chance: two random orders
            // of n questions agree with probability 1/n!, which for the 24 seeds is about 1.6e-24.
            assertTrue(pool.size >= 12, "too few seeds for the odds above to stay negligible")
            assertNotEquals(pool.ids(), other.ids(), "two players were served the same order")
        }

    @Test
    fun `once everything is answered the feed loops back over the answered questions`() =
        runServer("feed-loop") { client ->
            val player = client.guest()
            val pool = client.wholePool(player)
            pool.forEach { question -> client.vote(player, question.id, OptionSide.A) }

            val looped = client.wholePool(player)

            // Never empty while there are questions. Least recently answered first is pinned by
            // QuestionStoreTest, which picks the answer times: here answers can share a
            // millisecond, and the order among those is random.
            assertEquals(pool.ids().toSet(), looped.ids().toSet())
            assertEquals(looped.size, looped.ids().toSet().size, "a looped batch must not repeat a question")
            assertTrue(looped.all { it.answeredBefore }, "every question in the loop was answered before")
            assertEquals(1, client.batch(player, "?${WyrApi.Query.LIMIT}=1").size)
        }

    @Test
    fun `the last few unanswered questions come first and then the batch loops`() =
        runServer("feed-top-up") { client ->
            val player = client.guest()
            val pool = client.wholePool(player)
            val left = pool.take(3)
            val answered = pool.drop(3).ids()
            answered.forEach { id -> client.vote(player, id, OptionSide.B) }

            val batch = client.batch(player, "?${WyrApi.Query.LIMIT}=5")

            assertEquals(5, batch.size)
            assertEquals(left.ids().toSet(), batch.take(3).ids().toSet(), "the unanswered questions come first")
            assertTrue(batch.take(3).none { it.answeredBefore })
            assertTrue(batch.drop(3).all { it.answeredBefore && it.id in answered }, "then it loops")
        }

    @Test
    fun `a category filter applies to unanswered and looped questions alike`() =
        runServer("feed-category") { client ->
            val player = client.guest()
            val food = "&${WyrApi.Query.CATEGORY}=${QuestionCategory.FOOD.name}"
            val pool = client.wholePool(player)
            val foodPool = client.wholePool(player, food)
            assertTrue(foodPool.size >= 2 && foodPool.all { it.category == QuestionCategory.FOOD })

            val unanswered = foodPool.first()
            foodPool.drop(1).forEach { question -> client.vote(player, question.id, OptionSide.A) }
            val batch = client.wholePool(player, food)

            assertEquals(foodPool.ids().toSet(), batch.ids().toSet(), "only food, answered or not")
            assertEquals(unanswered.id, batch.first().id, "the one unanswered food question comes first")
            assertFalse(batch.first().answeredBefore)
            assertTrue(batch.drop(1).all { it.answeredBefore })
            // Unfiltered, only those food questions count as answered.
            assertEquals(pool.size - (foodPool.size - 1), client.wholePool(player).count { !it.answeredBefore })
        }

    @Test
    fun `a batch holds as many questions as asked for within bounds`() =
        runServer("feed-limit") { client ->
            val player = client.guest()
            val pool = client.wholePool(player)
            assertTrue(pool.size > WyrApi.Limits.DEFAULT_PAGE_SIZE, "too few seeds to tell the default apart")

            assertEquals(WyrApi.Limits.DEFAULT_PAGE_SIZE, client.batch(player).size)
            assertEquals(3, client.batch(player, "?${WyrApi.Query.LIMIT}=3").size)
            assertEquals(1, client.batch(player, "?${WyrApi.Query.LIMIT}=0").size, "a limit below 1 is raised to 1")
            assertEquals(pool.size, client.batch(player, "?${WyrApi.Query.LIMIT}=100000").size)

            val malformed = client.feed(player, "?${WyrApi.Query.LIMIT}=abc")
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            assertEquals(ErrorCode.VALIDATION_FAILED, malformed.body<ErrorDto>().code)
        }

    private fun runServer(
        databaseName: String,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
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

    private suspend fun HttpClient.guest(): SessionDto = post(WyrApi.Paths.AUTH_GUEST).body()

    private suspend fun HttpClient.feed(
        session: SessionDto,
        query: String = "",
    ): HttpResponse = get(WyrApi.Paths.QUESTIONS + query) { bearerAuth(session.accessToken) }

    private suspend fun HttpClient.batch(
        session: SessionDto,
        query: String = "",
    ): List<QuestionDto> = feed(session, query).body<QuestionPageDto>().questions

    /**
     * One batch at the maximum size: every question in the pool, or in the category [extraQuery]
     * names, for as long as the pool is smaller than that, as the seeds are.
     */
    private suspend fun HttpClient.wholePool(
        session: SessionDto,
        extraQuery: String = "",
    ): List<QuestionDto> {
        val batch = batch(session, "?${WyrApi.Query.LIMIT}=${WyrApi.Limits.MAX_PAGE_SIZE}$extraQuery")
        assertTrue(batch.size < WyrApi.Limits.MAX_PAGE_SIZE, "the pool no longer fits in one batch")
        return batch
    }

    private fun List<QuestionDto>.ids(): List<String> = map { it.id }

    private suspend fun HttpClient.vote(
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

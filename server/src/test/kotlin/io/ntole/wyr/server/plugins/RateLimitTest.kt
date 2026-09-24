package io.ntole.wyr.server.plugins

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.like.LikeRequest
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.core.question.SkipRequest
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.NO_PRACTICAL_LIMIT
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.config.RateLimits
import io.ntole.wyr.server.config.RequestBudget
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.rateLimitsOf
import io.ntole.wyr.server.testDatabaseFor
import io.ntole.wyr.server.wyrModule
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The rate limits end to end (CLAUDE.md §8b, *Rate limiting*): each group of routes refused past its
 * budget, before any of the route runs, per player or per client address as the group is keyed.
 */
class RateLimitTest {
    @Test
    fun `every group refuses the request past its budget with 429, RATE_LIMITED and Retry-After`() {
        groups.forEach { group ->
            runServer("group-${group.name}", group.limitedTo(NO_PRACTICAL_LIMIT, TWO_A_MINUTE)) { client ->
                val caller = Caller(client, if (group.needsSession) client.guest() else null)

                repeat(TWO_A_MINUTE.requests) { index ->
                    assertEquals(group.allowed, group.send(caller).status, "${group.name} request ${index + 1}")
                }
                val refused = group.send(caller)

                assertRateLimited(refused, group.name)
            }
        }
    }

    @Test
    fun `a refused request does none of its work`() =
        runServer("no-work", NO_PRACTICAL_LIMIT.copy(votes = TWO_A_MINUTE, submissions = TWO_A_MINUTE)) { client ->
            val player = client.guest()

            repeat(2) { assertEquals(HttpStatusCode.OK, client.vote(player).status) }
            assertRateLimited(client.vote(player), "the third vote")
            repeat(2) { index -> assertEquals(HttpStatusCode.Created, client.submit(player, "Paid $index").status) }
            assertRateLimited(client.submit(player, "Refused"), "the third submission")

            val stats = client.stats(player)
            assertEquals(2, stats.totalPoints, "the refused vote paid nothing")
            assertEquals(2, stats.answersGiven, "and was no answer")
            val submitted = client.mySubmissions(player).submissions.map { it.optionA }
            assertEquals(listOf("Paid 1", "Paid 0"), submitted, "the refused submission was not stored")
        }

    @Test
    fun `a refused refresh rotates nothing, so its token works once the budget is back`() =
        runServer("refresh-kept", NO_PRACTICAL_LIMIT.copy(refreshes = RequestBudget(1, per = 1.seconds))) { client ->
            val session = client.guest()
            val refreshed: SessionDto = client.refresh(session.refreshToken).body()

            assertRateLimited(client.refresh(refreshed.refreshToken), "the second refresh")
            delay(1.seconds + 200.milliseconds)

            val again = client.refresh(refreshed.refreshToken)
            assertEquals(HttpStatusCode.OK, again.status, "the refused refresh left the token live")
            assertEquals(session.playerId, again.body<SessionDto>().playerId)
        }

    @Test
    fun `players behind one address do not share a budget`() =
        runServer("per-player", NO_PRACTICAL_LIMIT.copy(votes = TWO_A_MINUTE)) { client ->
            val (first, second) = client.guest() to client.guest()

            repeat(2) { client.vote(first) }
            assertRateLimited(client.vote(first), "the first player's third vote")

            assertEquals(HttpStatusCode.OK, client.vote(second).status, "the second player's budget is their own")
        }

    @Test
    fun `a request with no valid token spends its address's budget and never a player's`() =
        runServer("no-token", NO_PRACTICAL_LIMIT.copy(votes = TWO_A_MINUTE)) { client ->
            val player = client.guest()
            // Names the player, but signed with another secret: were it read without being verified, it
            // would spend the player's own budget, and anyone could lock a player out by their id.
            val forged = forgedAccessToken(player.playerId)

            repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.vote(accessToken = forged).status) }
            assertRateLimited(client.vote(accessToken = null), "a third without a valid token")

            assertEquals(HttpStatusCode.OK, client.vote(player).status, "the player's budget was not touched")
        }

    @Test
    fun `health is in no group`() =
        runServer("health", rateLimitsOf(RequestBudget(requests = 1, per = 1.minutes))) { client ->
            repeat(10) { index ->
                assertEquals(HttpStatusCode.OK, client.get(WyrApi.Paths.HEALTH).status, "check ${index + 1}")
            }
        }

    @Test
    fun `wrong admin tokens are limited apart from the moderator's own requests`() =
        runServer("admin-failures", NO_PRACTICAL_LIMIT.copy(adminTokenFailures = TWO_A_MINUTE)) { client ->
            // More than the failure budget, and none of them spend it.
            repeat(5) { assertEquals(HttpStatusCode.OK, client.queue(ADMIN_TOKEN).status) }

            repeat(2) { assertEquals(HttpStatusCode.Forbidden, client.queue("wrong-token").status) }
            assertRateLimited(client.queue("wrong-token"), "a third wrong token")
            assertRateLimited(client.queue(token = null), "no token at all counts as wrong")

            assertEquals(HttpStatusCode.OK, client.queue(ADMIN_TOKEN).status, "the right token is still let in")
        }

    @Test
    fun `wrong admin tokens refused for their own budget spend none of the moderator's`() =
        runServer(
            "admin-order",
            NO_PRACTICAL_LIMIT.copy(admin = RequestBudget(5, 1.minutes), adminTokenFailures = TWO_A_MINUTE),
        ) { client ->
            // Two of the admin budget's five go on wrong tokens, which also spend the failure budget.
            repeat(2) { assertEquals(HttpStatusCode.Forbidden, client.queue("wrong-token").status) }
            // Refused by the failure budget first, so these leave the admin budget's three alone.
            repeat(3) { assertRateLimited(client.queue("wrong-token"), "wrong token ${it + 3}") }

            repeat(3) { assertEquals(HttpStatusCode.OK, client.queue(ADMIN_TOKEN).status, "the moderator's ${it + 1}") }
            assertRateLimited(client.queue(ADMIN_TOKEN), "past the admin budget itself")
        }

    @Test
    fun `the default limits let the console's longest run of answers through`() =
        runServer("defaults", RateLimits.DEFAULT) { client ->
            val player = client.guest()

            // As the console's Answer N at its most, 50: a batch at a time, each question answered.
            var answered = 0
            while (answered < CONSOLE_MOST_ANSWERS) {
                val batch = client.get(WyrApi.Paths.QUESTIONS) { bearerAuth(player.accessToken) }
                assertEquals(HttpStatusCode.OK, batch.status)
                batch.body<QuestionPageDto>().questions.take(CONSOLE_MOST_ANSWERS - answered).forEach { question ->
                    assertEquals(HttpStatusCode.OK, client.vote(player, question.id).status, "answer ${++answered}")
                }
            }

            assertEquals(CONSOLE_MOST_ANSWERS, client.stats(player).totalPoints)
        }

    @Test
    fun `a refused request is logged once, naming the limit and never a credential`() =
        withLogCapture { logged ->
            val limits =
                NO_PRACTICAL_LIMIT.copy(
                    votes = RequestBudget(1, 1.minutes),
                    guests = RequestBudget(1, 1.minutes),
                )
            // The mints' address comes from a trusted X-Forwarded-For, a header's value like the token.
            // The player's own mint came from the socket peer, an address of its own.
            runServer("logged", limits, trustedProxyHops = 2) { client ->
                val player = client.guest()
                client.vote(player)
                assertRateLimited(client.vote(player), "the second vote")
                client.post(WyrApi.Paths.AUTH_GUEST) { forwardedFor(CLIENT, PROXY) }
                assertRateLimited(client.post(WyrApi.Paths.AUTH_GUEST) { forwardedFor(CLIENT, PROXY) }, "a third guest")

                val events = logged.list.filter { "rate limit" in it.formattedMessage }
                assertEquals(2, events.size, "one line per refused request: ${events.map { it.formattedMessage }}")
                assertTrue(events.all { it.level.levelStr == "INFO" })
                val (vote, guest) = events.map { it.formattedMessage }
                assertTrue(
                    "rate limit votes reached for player ${player.playerId}: POST ${WyrApi.Paths.VOTES}" in vote,
                    vote,
                )
                assertFalse(player.accessToken in vote, "the token stays out of the log")
                assertTrue(
                    "rate limit guests reached for a client address: POST ${WyrApi.Paths.AUTH_GUEST}" in guest,
                    guest,
                )
                assertFalse(CLIENT in guest, "and so does the address")
            }
        }

    @Test
    fun `X-Forwarded-For is ignored unless proxies are trusted`() =
        runServer("forwarded-untrusted", NO_PRACTICAL_LIMIT.copy(guests = TWO_A_MINUTE)) { client ->
            // Were it read, each of these would be a client of its own, and none would be refused.
            repeat(2) { index -> client.post(WyrApi.Paths.AUTH_GUEST) { forwardedFor("203.0.113.$index") } }

            assertRateLimited(client.post(WyrApi.Paths.AUTH_GUEST) { forwardedFor("203.0.113.9") }, "the third mint")
        }

    @Test
    fun `behind trusted proxies each client address has a budget of its own`() =
        runServer(
            "forwarded-per-address",
            NO_PRACTICAL_LIMIT.copy(guests = TWO_A_MINUTE),
            trustedProxyHops = 2,
        ) { client ->
            suspend fun mintFrom(address: String) =
                client.post(WyrApi.Paths.AUTH_GUEST) { forwardedFor(address, PROXY) }

            repeat(2) { assertEquals(HttpStatusCode.OK, mintFrom(CLIENT).status) }
            assertRateLimited(mintFrom(CLIENT), "the client's third")

            assertEquals(HttpStatusCode.OK, mintFrom(OTHER_CLIENT).status, "another address behind the same proxies")
        }

    @Test
    fun `a client's own X-Forwarded-For entries do not change its address`() =
        runServer("forwarded-spoofed", NO_PRACTICAL_LIMIT.copy(guests = TWO_A_MINUTE), trustedProxyHops = 2) { client ->
            // The proxies append to what the client sent, so its own entries end up on the left.
            suspend fun mintClaiming(address: String) =
                client.post(WyrApi.Paths.AUTH_GUEST) { forwardedFor(address, CLIENT, PROXY) }

            repeat(2) { index -> assertEquals(HttpStatusCode.OK, mintClaiming("198.51.100.$index").status) }

            assertRateLimited(mintClaiming("198.51.100.99"), "a third mint claiming yet another address")
        }

    @Test
    fun `on Render with no trusted proxies the server warns at boot`() =
        withLogCapture { logged ->
            fun warnings() =
                logged.list
                    .filter { it.level.levelStr == "WARN" && "TRUSTED_PROXY_HOPS" in it.formattedMessage }
                    .map { it.formattedMessage }

            runServer(
                "render-untrusted",
                RateLimits.DEFAULT,
                onRender = true,
            ) { client -> client.get(WyrApi.Paths.HEALTH) }
            assertEquals(1, warnings().size, "every client would share each per-address budget")

            logged.list.clear()
            runServer("render-trusted", RateLimits.DEFAULT, trustedProxyHops = 3, onRender = true) { client ->
                client.get(WyrApi.Paths.HEALTH)
            }
            runServer("laptop", RateLimits.DEFAULT) { client -> client.get(WyrApi.Paths.HEALTH) }
            assertEquals(emptyList(), warnings())
        }

    /** A group of routes, and a request to it that its budget lets through as [allowed]. */
    private class Group(
        val name: String,
        /** The limits it is given, with this group's budget replaced. */
        val limitedTo: RateLimits.(RequestBudget) -> RateLimits,
        val allowed: HttpStatusCode = HttpStatusCode.OK,
        val needsSession: Boolean = true,
        val send: suspend (Caller) -> HttpResponse,
    )

    /** Whoever sends a group's requests: the client, and the session it sends them as, if any. */
    private class Caller(
        val client: HttpClient,
        val session: SessionDto?,
    ) {
        val player: SessionDto get() = assertNotNull(session, "this group needs a session")

        /** The refresh token the next refresh spends, rotated by each that works. */
        var refreshToken: String? = session?.refreshToken
        var sent = 0
    }

    private val groups =
        listOf(
            Group("guests", { copy(guests = it) }, needsSession = false) { caller ->
                caller.client.post(WyrApi.Paths.AUTH_GUEST)
            },
            Group("refreshes", { copy(refreshes = it) }) { caller ->
                caller.client.refresh(assertNotNull(caller.refreshToken)).also { response ->
                    if (response.status ==
                        HttpStatusCode.OK
                    ) {
                        caller.refreshToken = response.body<SessionDto>().refreshToken
                    }
                }
            },
            Group("feed", { copy(feed = it) }) { caller ->
                caller.client.get(WyrApi.Paths.QUESTIONS) { bearerAuth(caller.player.accessToken) }
            },
            Group("votes", { copy(votes = it) }) { caller -> caller.client.vote(caller.player) },
            Group("skips", { copy(skips = it) }, allowed = HttpStatusCode.NoContent) { caller ->
                caller.client.post(WyrApi.Paths.SKIPS) { json(caller.player, SkipRequest(SEED)) }
            },
            Group("likes", { copy(likes = it) }) { caller ->
                caller.client.post(WyrApi.Paths.LIKES) { json(caller.player, LikeRequest(SEED, liked = true)) }
            },
            Group("submissions", { copy(submissions = it) }, allowed = HttpStatusCode.Created) {
                it.client.submit(it.player, "Question ${it.sent++}")
            },
            Group("stats", { copy(stats = it) }) { caller ->
                caller.client.get(WyrApi.Paths.ME) { bearerAuth(caller.player.accessToken) }
            },
            Group("my submissions", { copy(mySubmissions = it) }) { caller ->
                caller.client.get(WyrApi.Paths.MY_QUESTIONS) { bearerAuth(caller.player.accessToken) }
            },
            Group("admin", { copy(admin = it) }, needsSession = false) { caller ->
                caller.client.queue(ADMIN_TOKEN)
            },
            Group(
                "admin token failures",
                { copy(adminTokenFailures = it) },
                allowed = HttpStatusCode.Forbidden,
                needsSession = false,
            ) { caller -> caller.client.queue("wrong-token") },
        )

    private suspend fun assertRateLimited(
        response: HttpResponse,
        what: String,
    ) {
        assertEquals(HttpStatusCode.TooManyRequests, response.status, what)
        assertEquals(ErrorCode.RATE_LIMITED, response.body<ErrorDto>().code, what)
        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
        assertNotNull(retryAfter, "$what carries Retry-After in whole seconds")
        assertTrue(retryAfter in 1..60, "$what: Retry-After $retryAfter is within the minute")
    }

    private fun runServer(
        databaseName: String,
        limits: RateLimits,
        trustedProxyHops: Int = 0,
        onRender: Boolean = false,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        val database = testDatabaseFor("rate-limit-$databaseName")
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
                adminToken = ADMIN_TOKEN,
                rateLimits = limits,
                trustedProxyHops = trustedProxyHops,
                onRender = onRender,
            )

        application { wyrModule(config) }

        block(createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }

    private companion object {
        const val ADMIN_TOKEN = "test-admin-token-0123456789abcdef"
        const val SEED = "seed-1"
        const val CONSOLE_MOST_ANSWERS = 50
        val TWO_A_MINUTE = RequestBudget(requests = 2, per = 1.minutes)

        /** A client, and the proxy that reached the server, as two trusted proxies record them. */
        const val CLIENT = "203.0.113.7"
        const val OTHER_CLIENT = "203.0.113.8"
        const val PROXY = "10.0.0.1"

        suspend fun HttpClient.guest(): SessionDto = post(WyrApi.Paths.AUTH_GUEST).body()

        fun HttpRequestBuilder.forwardedFor(vararg entries: String) {
            header(HttpHeaders.XForwardedFor, entries.joinToString(", "))
        }

        /** Runs [block] with every log event recorded, from any logger. */
        fun withLogCapture(block: (ListAppender<ILoggingEvent>) -> Unit) {
            val logged = ListAppender<ILoggingEvent>().apply { start() }
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            root.addAppender(logged)
            try {
                block(logged)
            } finally {
                root.detachAppender(logged)
            }
        }

        /** An access token for [playerId] as the server under test would issue it, but for its secret. */
        fun forgedAccessToken(playerId: String): String {
            val env =
                mapOf(
                    "JWT_SECRET" to "another-secret",
                    "JWT_ISSUER" to "wyr-test",
                    "JWT_AUDIENCE" to "wyr-test-client",
                )
            return TokenService(ServerConfig.fromEnvironment(env::get)).issueAccessToken(playerId)
        }

        suspend fun HttpClient.refresh(refreshToken: String): HttpResponse =
            post(WyrApi.Paths.AUTH_REFRESH) {
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(refreshToken))
            }

        suspend fun HttpClient.vote(
            session: SessionDto,
            questionId: String = SEED,
        ): HttpResponse = vote(session.accessToken, questionId)

        suspend fun HttpClient.vote(
            accessToken: String?,
            questionId: String = SEED,
        ): HttpResponse =
            post(WyrApi.Paths.VOTES) {
                accessToken?.let(::bearerAuth)
                contentType(ContentType.Application.Json)
                setBody(VoteRequest(questionId, OptionSide.A, attemptId = UUID.randomUUID().toString()))
            }

        suspend fun HttpClient.submit(
            session: SessionDto,
            text: String,
        ): HttpResponse =
            post(WyrApi.Paths.QUESTIONS) {
                json(session, SubmitQuestionRequest(text, "Not $text", listOf(QuestionCategory.FOOD)))
            }

        suspend fun HttpClient.stats(session: SessionDto): PlayerStatsDto =
            get(WyrApi.Paths.ME) { bearerAuth(session.accessToken) }.body()

        suspend fun HttpClient.mySubmissions(session: SessionDto): SubmissionListDto =
            get(WyrApi.Paths.MY_QUESTIONS) { bearerAuth(session.accessToken) }.body()

        /** The moderator's queue, asked for with [token], or with none. */
        suspend fun HttpClient.queue(token: String?): HttpResponse =
            get(WyrApi.Paths.ADMIN_SUBMISSIONS) { token?.let { header(WyrApi.Headers.ADMIN_TOKEN, it) } }

        inline fun <reified T : Any> HttpRequestBuilder.json(
            session: SessionDto,
            body: T,
        ) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
}

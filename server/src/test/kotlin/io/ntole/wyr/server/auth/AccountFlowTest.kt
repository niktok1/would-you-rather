package io.ntole.wyr.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
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
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.AccountDto
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.NO_PRACTICAL_LIMIT
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.printed
import io.ntole.wyr.server.testDatabaseFor
import io.ntole.wyr.server.withLogCapture
import io.ntole.wyr.server.wyrModule
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Accounts and the sessions they log into, end to end (CLAUDE.md §8a, *Accounts* and *Sessions*):
 * registering a guest, and logging a device out.
 */
class AccountFlowTest {
    @Test
    fun `a guest registers under its name lower-cased and keeps its points and its session`() =
        runServer("register") { client ->
            val guest = client.guest()
            assertEquals(HttpStatusCode.OK, client.vote(guest).status)

            val registered = client.register(guest, "Bob_1", PASSWORD)

            assertEquals(HttpStatusCode.OK, registered.status)
            assertEquals(AccountDto("bob_1"), registered.body<AccountDto>())
            val stats = client.stats(guest)
            assertEquals(1, stats.totalPoints, "the guest's point stays")
            assertEquals("bob_1", stats.username, "and the stats name it")
            val refreshed = client.refresh(guest.refreshToken)
            assertEquals(HttpStatusCode.OK, refreshed.status, "and so does its session")
            assertEquals(guest.playerId, refreshed.body<SessionDto>().playerId)
        }

    @Test
    fun `a name or password at the edge of the rules is taken`() =
        runServer("register-edges") { client ->
            listOf(
                "abc" to "123456",
                "a".repeat(WyrApi.Limits.MAX_USERNAME_LENGTH) to "x".repeat(WyrApi.Limits.MAX_PASSWORD_LENGTH),
                "___" to "      ",
                "0_z_9" to "ünïcødé 😀",
            ).forEach { (username, password) ->
                assertEquals(HttpStatusCode.OK, client.register(client.guest(), username, password).status, username)
            }
        }

    @Test
    fun `a name the rules refuse is 422 INVALID_USERNAME, checked before the password`() =
        runServer("register-bad-name") { client ->
            val guest = client.guest()
            listOf(
                "ab",
                "a".repeat(WyrApi.Limits.MAX_USERNAME_LENGTH + 1),
                "",
                "bob smith",
                " bob",
                "bob ",
                "bob-1",
                "bøb",
                "bob\n",
            ).forEach { username ->
                assertRefused(
                    client.register(guest, username, PASSWORD),
                    HttpStatusCode.UnprocessableEntity,
                    ErrorCode.INVALID_USERNAME,
                    username,
                )
            }
            assertRefused(
                client.register(guest, "ab", password = "short"),
                HttpStatusCode.UnprocessableEntity,
                ErrorCode.INVALID_USERNAME,
                "both wrong",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.register(guest, "bob", PASSWORD).status,
                "none of them registered it",
            )
        }

    @Test
    fun `a password the rules refuse is 422 INVALID_PASSWORD`() =
        runServer("register-bad-password") { client ->
            val guest = client.guest()
            listOf("", "12345", "x".repeat(WyrApi.Limits.MAX_PASSWORD_LENGTH + 1)).forEach { password ->
                assertRefused(
                    client.register(guest, "bob", password),
                    HttpStatusCode.UnprocessableEntity,
                    ErrorCode.INVALID_PASSWORD,
                    "${password.length} characters",
                )
            }
            assertEquals(
                HttpStatusCode.OK,
                client.register(guest, "bob", PASSWORD).status,
                "none of them registered it",
            )
        }

    @Test
    fun `a name another player has in any case is 409 USERNAME_TAKEN`() =
        runServer("register-taken") { client ->
            assertEquals(HttpStatusCode.OK, client.register(client.guest(), "bob", PASSWORD).status)
            val second = client.guest()

            listOf("bob", "BOB", "Bob").forEach { username ->
                assertRefused(
                    client.register(second, username, PASSWORD),
                    HttpStatusCode.Conflict,
                    ErrorCode.USERNAME_TAKEN,
                    username,
                )
            }
            assertEquals(HttpStatusCode.OK, client.register(second, "bob2", PASSWORD).status, "another name is free")
        }

    @Test
    fun `a player registers once and every registration after is 409 ALREADY_REGISTERED`() =
        runServer("register-twice") { client ->
            val guest = client.guest()
            assertEquals(HttpStatusCode.OK, client.register(guest, "bob", PASSWORD).status)

            listOf("bob" to PASSWORD, "carol" to "another password").forEach { (username, password) ->
                assertRefused(
                    client.register(guest, username, password),
                    HttpStatusCode.Conflict,
                    ErrorCode.ALREADY_REGISTERED,
                    username,
                )
            }
        }

    @Test
    fun `registering needs a session of a player that exists`() =
        runServer("register-unauthorized") { client ->
            val nobody =
                client.post(WyrApi.Paths.AUTH_REGISTER) {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("bob", PASSWORD))
                }
            val ghost = client.register(accessToken = accessTokenFor("no-such-player"), "bob", PASSWORD)

            assertRefused(nobody, HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "no token")
            assertRefused(ghost, HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "a player that does not exist")
        }

    @Test
    fun `a registration body the server cannot use is a validation error`() =
        runServer("register-malformed") { client ->
            val guest = client.guest()
            listOf("", "not json", "{}", """{"username":"bob"}""", """{"password":"$PASSWORD"}""").forEach { body ->
                val response =
                    client.post(WyrApi.Paths.AUTH_REGISTER) {
                        bearerAuth(guest.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                assertRefused(response, HttpStatusCode.BadRequest, ErrorCode.VALIDATION_FAILED, body)
            }
        }

    @Test
    fun `no password or hash of one reaches the log, whatever a registration is answered`() =
        withLogCapture { logged ->
            runServer("register-log") { client ->
                val (first, second) = client.guest() to client.guest()
                client.register(first, "bob", PASSWORD)
                client.register(second, "bob", PASSWORD)
                client.register(second, "carol", TOO_SHORT)
                client.register(first, "bob", PASSWORD)
            }

            val printed = logged.printed()
            assertTrue(printed.isNotEmpty(), "the calls are logged")
            assertFalse(printed.any { PASSWORD in it || TOO_SHORT in it || "pbkdf2" in it }, "$printed")
        }

    /**
     * The access token names its session, which a refresh keeps, so the mint's token, still valid,
     * logs out the session the refresh rotated.
     */
    @Test
    fun `logging out ends this device's session, so neither its token nor the one the grace keeps works again`() =
        runServer("logout") { client ->
            val minted = client.guest()
            val refreshed = client.refresh(minted.refreshToken).body<SessionDto>()
            assertEquals(sessionIdIn(minted), sessionIdIn(refreshed), "a refresh keeps the session")

            val loggedOut = client.logout(minted.accessToken)

            assertEquals(HttpStatusCode.NoContent, loggedOut.status)
            val tokens = listOf("its current token" to refreshed.refreshToken, "the one before" to minted.refreshToken)
            tokens.forEach { (case, token) ->
                assertRefused(client.refresh(token), HttpStatusCode.Unauthorized, ErrorCode.INVALID_REFRESH_TOKEN, case)
            }
            assertEquals(HttpStatusCode.NoContent, client.logout(refreshed.accessToken).status, "a second logout")
        }

    @Test
    fun `every mint opens a session of its own`() =
        runServer("logout-sessions") { client ->
            val (first, second) = client.guest() to client.guest()
            assertTrue(sessionIdIn(first) != sessionIdIn(second))

            assertEquals(HttpStatusCode.NoContent, client.logout(first.accessToken).status)

            assertEquals(HttpStatusCode.OK, client.refresh(second.refreshToken).status, "another's session lives on")
        }

    /**
     * A token from a build before tokens named their session, such as one production's `d4a9dbf`
     * issued: refused 401, which a client answers by refreshing, and the refreshed token names it.
     */
    @Test
    fun `logging out needs a token that names a session`() =
        runServer("logout-unauthorized") { client ->
            val guest = client.guest()
            val nobody = client.post(WyrApi.Paths.AUTH_LOGOUT)
            val unnamed = client.logout(accessTokenNamingNoSession(guest.playerId))

            assertRefused(nobody, HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "no token")
            assertRefused(unnamed, HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "a token naming no session")
            val refreshed = client.refresh(guest.refreshToken)
            assertEquals(HttpStatusCode.OK, refreshed.status, "the session lives on")
            assertEquals(HttpStatusCode.NoContent, client.logout(refreshed.body<SessionDto>().accessToken).status)
        }

    @Test
    fun `a registration request prints no password`() {
        val printed = RegisterRequest("bob", PASSWORD).toString()

        assertTrue("bob" in printed)
        assertFalse(PASSWORD in printed, printed)
    }

    private suspend fun assertRefused(
        response: HttpResponse,
        status: HttpStatusCode,
        code: ErrorCode,
        case: String,
    ) {
        assertEquals(status, response.status, case)
        assertEquals(code, response.body<ErrorDto>().code, case)
    }

    private fun runServer(
        databaseName: String,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        val database = testDatabaseFor("accounts-$databaseName")
        application { wyrModule(config(database.jdbcUrl, database.user, database.password)) }
        block(createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"

        /** One character short of a password, and in no log line but a leaked one. */
        const val TOO_SHORT = "Ab3%!"

        fun config(
            jdbcUrl: String,
            user: String?,
            password: String?,
        ) = ServerConfig(
            port = 0,
            jdbcUrl = jdbcUrl,
            dbUser = user,
            dbPassword = password,
            jwtSecret = "test-secret",
            jwtIssuer = "wyr-test",
            jwtAudience = "wyr-test-client",
            accessTokenTtlSeconds = 300,
            refreshTokenTtlSeconds = 3_600,
            refreshGraceSeconds = null,
            allowedWebOrigins = emptyList(),
            adminToken = null,
            rateLimits = NO_PRACTICAL_LIMIT,
            clientIpHeader = null,
            onRender = false,
        )

        /** An access token for [playerId] as the server under test signs one. */
        fun accessTokenFor(playerId: String): String =
            TokenService(config("jdbc:h2:mem:unused", null, null)).issueAccessToken(playerId, "no-such-session")

        /** An access token as a build before tokens named their session signed one, for [playerId]. */
        fun accessTokenNamingNoSession(playerId: String): String =
            JWT
                .create()
                .withIssuer("wyr-test")
                .withAudience("wyr-test-client")
                .withSubject(playerId)
                .withClaim(TokenService.CLAIM_PLAYER_ID, playerId)
                .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
                .sign(Algorithm.HMAC256("test-secret"))

        /** The session [session]'s access token names. */
        fun sessionIdIn(session: SessionDto): String? =
            JWT.decode(session.accessToken).getClaim(TokenService.CLAIM_SESSION_ID).asString()

        suspend fun HttpClient.guest(): SessionDto = post(WyrApi.Paths.AUTH_GUEST).body()

        suspend fun HttpClient.logout(accessToken: String): HttpResponse =
            post(WyrApi.Paths.AUTH_LOGOUT) { bearerAuth(accessToken) }

        suspend fun HttpClient.register(
            session: SessionDto,
            username: String,
            password: String,
        ): HttpResponse = register(session.accessToken, username, password)

        suspend fun HttpClient.register(
            accessToken: String,
            username: String,
            password: String,
        ): HttpResponse =
            post(WyrApi.Paths.AUTH_REGISTER) {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest(username, password))
            }

        suspend fun HttpClient.refresh(refreshToken: String): HttpResponse =
            post(WyrApi.Paths.AUTH_REFRESH) {
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(refreshToken))
            }

        suspend fun HttpClient.vote(session: SessionDto): HttpResponse =
            post(WyrApi.Paths.VOTES) {
                bearerAuth(session.accessToken)
                contentType(ContentType.Application.Json)
                setBody(VoteRequest("seed-1", OptionSide.A, attemptId = UUID.randomUUID().toString()))
            }

        suspend fun HttpClient.stats(session: SessionDto): PlayerStatsDto =
            get(WyrApi.Paths.ME) { bearerAuth(session.accessToken) }.body()
    }
}

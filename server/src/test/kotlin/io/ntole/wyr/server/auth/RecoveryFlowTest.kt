package io.ntole.wyr.server.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.GuestSessionDto
import io.ntole.wyr.core.auth.RecoverRequest
import io.ntole.wyr.core.auth.RecoverySecretDto
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.NO_PRACTICAL_LIMIT
import io.ntole.wyr.server.TestDatabaseSettings
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.inTransaction
import io.ntole.wyr.server.db.serverPool
import io.ntole.wyr.server.testDatabaseFor
import io.ntole.wyr.server.wyrModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Recovery end to end (CLAUDE.md §8a, *Sessions* and *Recovery*): the secret a mint hands out, the
 * sessions it opens, each a device of its own, and the new secret that replaces it.
 */
class RecoveryFlowTest {
    @Test
    fun `a guest's mint carries a recovery secret of its own, and neither a refresh nor a recovery ever does`() =
        runServer("secret-once") { client ->
            val mint = client.post(WyrApi.Paths.AUTH_GUEST)
            val other = client.guest()

            val guest = Json.decodeFromString<GuestSessionDto>(mint.bodyAsText())
            val secret = assertNotNull(guest.recoverySecret)
            assertTrue(secret.length >= MIN_SECRET_LENGTH, "256 random bits: $secret")
            assertNotEquals(secret, other.recoverySecret, "each guest its own")
            assertNotEquals(guest.refreshToken, secret)
            val refreshed = client.refresh(guest.refreshToken)
            val recovered = client.recover(secret)
            assertEquals(HttpStatusCode.OK, refreshed.status)
            assertEquals(HttpStatusCode.OK, recovered.status)
            listOf("a refresh" to refreshed, "a recovery" to recovered).forEach { (what, answer) ->
                val fields = Json.parseToJsonElement(answer.bodyAsText()).jsonObject.keys
                assertEquals(SESSION_FIELDS, fields, "$what answers a session and nothing else")
                assertFalse(secret in answer.bodyAsText(), what)
            }
        }

    /**
     * The point of recovery: a phone that replaced the one the player played on comes back to the same
     * player, points and all, in a session of its own, while the old phone's session, if it still runs,
     * lives on beside it.
     */
    @Test
    fun `a recovery opens a session of its own for the secret's player, their points and all`() =
        runServer("recover") { client ->
            val phone = client.guest()
            client.vote(phone, "seed-1")

            val restored = client.recoveredSession(assertNotNull(phone.recoverySecret))

            assertEquals(phone.playerId, restored.playerId, "the same player, not a fresh guest")
            assertNotEquals(phone.refreshToken, restored.refreshToken, "a session of its own")
            assertEquals(1, client.stats(restored.accessToken).totalPoints, "with the point they had")
        }

    /** Sessions (CLAUDE.md §8a): each device rotates its own tokens, however often the other does. */
    @Test
    fun `a refresh on one device never logs out another`() =
        runServer("devices") { client ->
            val phone = client.guest()
            var tablet = client.recoveredSession(assertNotNull(phone.recoverySecret))

            repeat(3) {
                val refreshed = client.refresh(tablet.refreshToken)
                assertEquals(HttpStatusCode.OK, refreshed.status, "the tablet's refresh ${it + 1}")
                tablet = refreshed.body()
            }
            val phoneRefreshed = client.refresh(phone.refreshToken)

            assertEquals(HttpStatusCode.OK, phoneRefreshed.status, "the phone's first token, current all along")
            assertEquals(phone.playerId, phoneRefreshed.body<SessionDto>().playerId)
            assertEquals(HttpStatusCode.OK, client.refresh(tablet.refreshToken).status, "and the tablet's still")
        }

    /** The copy a restored phone has may lag, so presenting the secret spends nothing. */
    @Test
    fun `a recovery secret recovers again, as often as it is presented`() =
        runServer("recover-again") { client ->
            val phone = client.guest()
            val secret = assertNotNull(phone.recoverySecret)

            val sessions = List(3) { client.recoveredSession(secret) }

            assertEquals(setOf(phone.playerId), sessions.map { it.playerId }.toSet())
            assertEquals(3, sessions.map { it.refreshToken }.toSet().size, "a session each time")
        }

    /**
     * Every secret no player holds is refused alike, 401 and never 404, so a refusal tells a guess
     * nothing: one never issued, one replaced, and a live refresh token, which is no secret.
     */
    @Test
    fun `a secret no player holds is refused alike as INVALID_RECOVERY_SECRET`() =
        runServer("recover-refused") { client ->
            val phone = client.guest()
            val replaced = assertNotNull(phone.recoverySecret)
            client.newSecret(phone.accessToken)

            listOf(
                "never issued" to "no-such-secret",
                "replaced" to replaced,
                "a refresh token" to phone.refreshToken,
            ).forEach { (case, secret) ->
                val refused = client.recover(secret)

                assertEquals(HttpStatusCode.Unauthorized, refused.status, case)
                assertEquals(ErrorCode.INVALID_RECOVERY_SECRET, refused.body<ErrorDto>().code, case)
            }
            assertEquals(HttpStatusCode.OK, client.refresh(phone.refreshToken).status, "the refresh token untouched")
        }

    @Test
    fun `a recovery body the server cannot use is a validation error`() =
        runServer("recover-malformed") { client ->
            listOf(
                "blank secret" to """{"recoverySecret":"   "}""",
                "empty secret" to """{"recoverySecret":""}""",
                "no secret" to """{}""",
                "malformed json" to "{not json",
            ).forEach { (case, body) ->
                val response =
                    client.post(WyrApi.Paths.AUTH_RECOVER) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }
        }

    /**
     * A new secret kills the one before it, since whoever held that one owns the account until it is
     * replaced (CLAUDE.md §8a, *Recovery*). It closes none of the sessions the old one opened.
     */
    @Test
    fun `a new recovery secret kills the one before it, and the sessions that one opened live on`() =
        runServer("new-secret") { client ->
            val phone = client.guest()
            val old = assertNotNull(phone.recoverySecret)
            val restored = client.recoveredSession(old)

            val issued = client.newSecret(phone.accessToken)

            assertEquals(HttpStatusCode.OK, issued.status)
            val new = issued.body<RecoverySecretDto>().recoverySecret
            assertNotEquals(old, new)
            assertEquals(HttpStatusCode.Unauthorized, client.recover(old).status, "the old one is dead")
            assertEquals(phone.playerId, client.recoveredSession(new).playerId)
            assertEquals(HttpStatusCode.OK, client.refresh(restored.refreshToken).status, "the old one's session")
            assertNotEquals(new, client.newSecret(phone.accessToken).body<RecoverySecretDto>().recoverySecret)
        }

    /** A guest minted before V4 has no secret until they ask for one, and recovers once they have. */
    @Test
    fun `a guest from before recovery asks for a secret and then recovers with it`() {
        val database = testDatabaseFor("recovery-before-v4")
        runServer("recovery-before-v4", database) { client ->
            val guest = client.guest()
            database.forgetRecoverySecret(guest.playerId)
            assertEquals(HttpStatusCode.Unauthorized, client.recover(assertNotNull(guest.recoverySecret)).status)

            val secret = client.newSecret(guest.accessToken).body<RecoverySecretDto>().recoverySecret

            assertEquals(guest.playerId, client.recoveredSession(secret).playerId)
        }
    }

    @Test
    fun `a new recovery secret needs a session of a player the server has`() =
        runServer("new-secret-refused") { client ->
            listOf(
                "no token" to null,
                "a player the server never had" to signedFor("no-such-player"),
                "another server's signature" to signedFor(client.guest().playerId, secret = "another-secret"),
            ).forEach { (case, token) ->
                val refused = client.newSecret(token)

                assertEquals(HttpStatusCode.Unauthorized, refused.status, case)
                assertEquals(ErrorCode.UNAUTHORIZED, refused.body<ErrorDto>().code, case)
            }
        }

    private fun runServer(
        databaseName: String,
        database: TestDatabaseSettings = testDatabaseFor(databaseName),
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        val config =
            ServerConfig(
                port = 0,
                jdbcUrl = database.jdbcUrl,
                dbUser = database.user,
                dbPassword = database.password,
                jwtSecret = JWT_SECRET,
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

        application { wyrModule(config) }

        block(createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }

    /** Takes [playerId]'s recovery secret away, as V4 left every player it found. */
    private fun TestDatabaseSettings.forgetRecoverySecret(playerId: String) =
        serverPool().use { pool ->
            pool.inTransaction {
                val forgotten = Players.update({ Players.id eq playerId }) { row -> row[recoverySecretHash] = null }
                check(forgotten == 1) { "no player $playerId" }
            }
        }

    private suspend fun HttpClient.guest(): GuestSessionDto = post(WyrApi.Paths.AUTH_GUEST).body()

    private suspend fun HttpClient.refresh(refreshToken: String): HttpResponse =
        post(WyrApi.Paths.AUTH_REFRESH) {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }

    private suspend fun HttpClient.recover(secret: String): HttpResponse =
        post(WyrApi.Paths.AUTH_RECOVER) {
            contentType(ContentType.Application.Json)
            setBody(RecoverRequest(secret))
        }

    private suspend fun HttpClient.recoveredSession(secret: String): SessionDto {
        val recovered = recover(secret)
        assertEquals(HttpStatusCode.OK, recovered.status)
        return recovered.body()
    }

    private suspend fun HttpClient.newSecret(accessToken: String?): HttpResponse =
        post(WyrApi.Paths.MY_RECOVERY_SECRET) { accessToken?.let(::bearerAuth) }

    private suspend fun HttpClient.vote(
        session: GuestSessionDto,
        questionId: String,
    ) = assertEquals(
        HttpStatusCode.OK,
        post(WyrApi.Paths.VOTES) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(VoteRequest(questionId, OptionSide.A, attemptId = UUID.randomUUID().toString()))
        }.status,
    )

    private suspend fun HttpClient.stats(accessToken: String): PlayerStatsDto =
        get(WyrApi.Paths.ME) { bearerAuth(accessToken) }.body()

    /** An access token for [playerId], signed as the server under test signs one unless [secret] says otherwise. */
    private fun signedFor(
        playerId: String,
        secret: String = JWT_SECRET,
    ): String {
        val env = mapOf("JWT_SECRET" to secret, "JWT_ISSUER" to "wyr-test", "JWT_AUDIENCE" to "wyr-test-client")
        return TokenService(ServerConfig.fromEnvironment(env::get)).issueAccessToken(playerId)
    }

    private companion object {
        const val JWT_SECRET = "test-secret"

        /** 32 bytes in unpadded base64url. */
        const val MIN_SECRET_LENGTH = 43

        /** Every field of a session, as `encodeDefaults` sends each one. */
        val SESSION_FIELDS = setOf("playerId", "accessToken", "refreshToken", "accessTokenExpiresInSeconds")
    }
}

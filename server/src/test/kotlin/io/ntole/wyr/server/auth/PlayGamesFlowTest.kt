package io.ntole.wyr.server.auth

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
import io.ntole.wyr.core.auth.PlayGamesSignInRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.auth.FakePlayGames.Companion.json
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.testDatabaseFor
import io.ntole.wyr.server.testServerConfig
import io.ntole.wyr.server.wyrModule
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Signing in with Play Games end to end (CLAUDE.md §8a, *Play Games sign-in*), with Google answered by
 * a MockEngine: nothing here reaches Google. Each code in [PLAYERS] names its Play Games player; any
 * other is refused as spent.
 */
class PlayGamesFlowTest {
    @Test
    fun `a first sign-in with no session mints a player signed in with Play Games`() =
        runServer("mint") { client, _ ->
            val session: SessionDto = client.signIn(code = "anna-phone").body()

            val stats = client.stats(session)
            assertTrue(stats.playGamesLinked)
            assertNull(stats.username)
            assertEquals(0, stats.totalPoints)
        }

    @Test
    fun `a guest's sign-in links its Play Games player to the guest who keeps everything`() =
        runServer("link") { client, _ ->
            val guest = client.guest()
            client.vote(guest)

            val session: SessionDto = client.signIn(code = "anna-phone", bearer = guest).body()

            assertEquals(guest.playerId, session.playerId)
            val stats = client.stats(session)
            assertTrue(stats.playGamesLinked)
            assertEquals(1, stats.totalPoints, "the guest's point came along")
            assertEquals(
                HttpStatusCode.OK,
                client
                    .get(WyrApi.Paths.ME) {
                        bearerAuth(guest.accessToken)
                    }.status,
                "its old session is left alone",
            )
        }

    @Test
    fun `another device signing in with the same Play Games player plays as the linked player`() =
        runServer("second-device") { client, _ ->
            val phone = client.guest()
            client.vote(phone)
            client.signIn(code = "anna-phone", bearer = phone)

            val tablet: SessionDto = client.signIn(code = "anna-tablet").body()

            assertEquals(phone.playerId, tablet.playerId)
            assertEquals(1, client.stats(tablet).totalPoints)
        }

    @Test
    fun `a guest meeting a Play Games player linked to another switches to it`() =
        runServer("switch") { client, _ ->
            val owner: SessionDto = client.signIn(code = "anna-phone").body()
            val stranger = client.guest()

            val switched: SessionDto = client.signIn(code = "anna-tablet", bearer = stranger).body()

            assertEquals(owner.playerId, switched.playerId)
            assertFalse(client.stats(stranger).playGamesLinked, "the guest it left is linked to nothing")
        }

    @Test
    fun `a player signed in with Play Games may submit as a registered one may`() =
        runServer("submit") { client, _ ->
            val session: SessionDto = client.signIn(code = "anna-phone").body()
            client.vote(session)

            val response =
                client.post(WyrApi.Paths.QUESTIONS) {
                    bearerAuth(session.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(SubmitQuestionRequest("Пица", "Бурек", listOf("FOOD")))
                }

            assertEquals(HttpStatusCode.Created, response.status)
        }

    /** Before the code is sent anywhere, so the refreshed retry can still spend it. */
    @Test
    fun `an expired session is refused before Google is asked`() =
        runServer("expired") { client, google ->
            val guest = client.guest()
            val expired =
                TokenService(TEST_CONFIG).issueAccessToken(
                    guest.playerId,
                    "any-session",
                    now =
                        System.currentTimeMillis() - 3_600_000,
                )

            val response =
                client.post(WyrApi.Paths.AUTH_PLAY_GAMES) {
                    bearerAuth(expired)
                    contentType(ContentType.Application.Json)
                    setBody(PlayGamesSignInRequest("anna-phone"))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
            assertEquals(emptyList(), google.exchanges)
        }

    @Test
    fun `a code Google refuses is 422 and links nothing`() =
        runServer("refused") { client, _ ->
            val guest = client.guest()

            val response = client.signIn(code = "spent-code", bearer = guest)

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(ErrorCode.PLAY_GAMES_CODE_REFUSED, response.body<ErrorDto>().code)
            assertFalse(client.stats(guest).playGamesLinked)
        }

    @Test
    fun `Google not answering is 502 and links nothing`() =
        runServer(
            "unavailable",
            FakePlayGames(token = { json("down", HttpStatusCode.ServiceUnavailable) }),
        ) { client, _ ->
            val guest = client.guest()

            val response = client.signIn(code = "anna-phone", bearer = guest)

            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertEquals(ErrorCode.PLAY_GAMES_UNAVAILABLE, response.body<ErrorDto>().code)
            assertFalse(client.stats(guest).playGamesLinked)
        }

    @Test
    fun `a malformed sign-in is refused before Google is asked`() =
        runServer("malformed") { client, google ->
            val bodies =
                listOf(
                    "{}",
                    """{"serverAuthCode":""}""",
                    """{"serverAuthCode":"has space"}""",
                    """{"serverAuthCode":"${"x".repeat(WyrApi.Limits.MAX_SERVER_AUTH_CODE_LENGTH + 1)}"}""",
                    "not json",
                )

            bodies.forEach { body ->
                val response =
                    client.post(WyrApi.Paths.AUTH_PLAY_GAMES) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, body.take(40))
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, body.take(40))
            }
            assertEquals(emptyList(), google.exchanges)
        }

    @Test
    fun `without Play Games configured there is no such route`() {
        val database = testDatabaseFor("play-games-off")
        testApplication {
            application {
                wyrModule(
                    testServerConfig(database),
                    TEST_SEEDS,
                ) { fail("no engine is made for Google while Play Games is off") }
            }
            val response =
                jsonClient().post(WyrApi.Paths.AUTH_PLAY_GAMES) {
                    contentType(ContentType.Application.Json)
                    setBody(PlayGamesSignInRequest("anna-phone"))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `the answer is a new session of its own`() =
        runServer("sessions") { client, _ ->
            val first: SessionDto = client.signIn(code = "anna-phone").body()
            val second: SessionDto = client.signIn(code = "anna-tablet").body()

            assertEquals(first.playerId, second.playerId)
            assertNotEquals(first.refreshToken, second.refreshToken)
            assertEquals(HttpStatusCode.OK, client.get(WyrApi.Paths.ME) { bearerAuth(first.accessToken) }.status)
        }

    private fun runServer(
        name: String,
        google: FakePlayGames = FakePlayGames(players = PLAYERS),
        block: suspend (HttpClient, FakePlayGames) -> Unit,
    ) = testApplication {
        val database = testDatabaseFor("play-games-$name")
        val config = testServerConfig(database).copy(playGames = TEST_PLAY_GAMES_CLIENT)
        application { wyrModule(config, TEST_SEEDS) { google.engine } }
        block(jsonClient(), google)
    }

    private fun ApplicationTestBuilder.jsonClient(): HttpClient =
        createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private suspend fun HttpClient.guest(): SessionDto = post(WyrApi.Paths.AUTH_GUEST).body()

    private suspend fun HttpClient.signIn(
        code: String,
        bearer: SessionDto? = null,
    ): HttpResponse =
        post(WyrApi.Paths.AUTH_PLAY_GAMES) {
            bearer?.let { bearerAuth(it.accessToken) }
            contentType(ContentType.Application.Json)
            setBody(PlayGamesSignInRequest(code))
        }.also { response ->
            if (code in PLAYERS && response.status != HttpStatusCode.OK &&
                response.status != HttpStatusCode.BadGateway
            ) {
                fail("signing in with $code: ${response.status}")
            }
        }

    private suspend fun HttpClient.stats(session: SessionDto): PlayerStatsDto =
        get(WyrApi.Paths.ME) { bearerAuth(session.accessToken) }.body()

    private suspend fun HttpClient.vote(session: SessionDto) =
        post(WyrApi.Paths.VOTES) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(VoteRequest("seed-1", OptionSide.A, UUID.randomUUID().toString()))
        }

    private companion object {
        /** Two codes, from two devices, of one Play Games player. */
        val PLAYERS = mapOf("anna-phone" to "g-anna", "anna-tablet" to "g-anna")

        /** The secret, issuer and audience `testServerConfig` signs with, for signing a token of the tests' own. */
        val TEST_CONFIG =
            ServerConfig.fromEnvironment(
                mapOf(
                    "JWT_SECRET" to "test-secret",
                    "JWT_ISSUER" to "wyr-test",
                    "JWT_AUDIENCE" to "wyr-test-client",
                    "ACCESS_TTL_SECONDS" to "300",
                )::get,
            )
    }
}

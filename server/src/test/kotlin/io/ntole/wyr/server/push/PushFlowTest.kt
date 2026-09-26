package io.ntole.wyr.server.push

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.LoginRequest
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.push.PushPlatform
import io.ntole.wyr.core.push.PushTokenRequest
import io.ntole.wyr.core.push.RemovePushTokenRequest
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.TestDatabaseSettings
import io.ntole.wyr.server.db.PushTokens
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.db.inTransaction
import io.ntole.wyr.server.db.serverPool
import io.ntole.wyr.server.testDatabaseFor
import io.ntole.wyr.server.testServerConfig
import io.ntole.wyr.server.wyrModule
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Push tokens and a decision's pushes end to end (CLAUDE.md §8a, *Push tokens*), with Google answered
 * by a MockEngine: nothing here reaches Google.
 */
class PushFlowTest {
    @Test
    fun `a device registers its token and removes it`() =
        runServer("register") { client, _ ->
            val session = client.guest()

            assertEquals(HttpStatusCode.NoContent, client.registerToken(session, "token-1").status)
            assertEquals(listOf("token-1" to session.playerId), tokens())

            assertEquals(HttpStatusCode.NoContent, client.removeToken(session, "token-1").status)
            assertEquals(emptyList(), tokens())
        }

    @Test
    fun `a token or platform the rules refuse is malformed and nothing is kept`() =
        runServer("refused") { client, _ ->
            val session = client.guest()
            val refused =
                listOf(
                    """{"token":"","platform":"ANDROID"}""",
                    """{"token":"has a space","platform":"ANDROID"}""",
                    """{"token":"tab\there","platform":"ANDROID"}""",
                    """{"token":"${"x".repeat(WyrApi.Limits.MAX_PUSH_TOKEN_LENGTH + 1)}","platform":"ANDROID"}""",
                    """{"token":"token-1"}""",
                    """{"token":"token-1","platform":"UNKNOWN"}""",
                    """{"token":"token-1","platform":"HARMONY"}""",
                    "not json",
                )

            refused.forEach { body ->
                val response =
                    client.post(WyrApi.Paths.MY_PUSH_TOKENS) {
                        bearerAuth(session.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, body.take(60))
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, body.take(60))
            }
            val longest = "x".repeat(WyrApi.Limits.MAX_PUSH_TOKEN_LENGTH)
            assertEquals(HttpStatusCode.NoContent, client.registerToken(session, longest).status, "the longest is kept")
            assertEquals(listOf(longest to session.playerId), tokens())
        }

    @Test
    fun `a token needs a session`() =
        runServer("no-session") { client, _ ->
            val response =
                client.post(WyrApi.Paths.MY_PUSH_TOKENS) {
                    contentType(ContentType.Application.Json)
                    setBody(PushTokenRequest("token-1", PushPlatform.ANDROID))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(emptyList(), tokens())
        }

    @Test
    fun `a logout stops the pushes to its device by itself`() =
        runServer("logout") { client, _ ->
            val session = client.guest()
            client.registerToken(session, "token-1")

            assertEquals(
                HttpStatusCode.NoContent,
                client.post(WyrApi.Paths.AUTH_LOGOUT) { bearerAuth(session.accessToken) }.status,
            )

            assertEquals(emptyList(), tokens())
            // Its access token still works until it expires, but no token can be kept under the ended session.
            assertEquals(HttpStatusCode.Unauthorized, client.registerToken(session, "token-2").status)
        }

    @Test
    fun `an approval is pushed to every device of its author`() =
        runServer("approval") { client, google ->
            val phone = client.registered("anna")
            val tablet = client.login("anna")
            client.registerToken(phone, "anna-phone")
            client.registerToken(tablet, "anna-tablet")
            client.registerToken(client.guest(), "someone-else")
            val question = client.submit(phone, "Пица", "Бурек")

            val approved = client.post(WyrApi.Paths.ADMIN_APPROVALS) { admin(ApproveSubmissionRequest(question.id)) }

            assertEquals(HttpStatusCode.OK, approved.status)
            val pushes = List(2) { google.nextPush() }
            assertEquals(setOf("anna-phone", "anna-tablet"), pushes.map { it.token }.toSet())
            pushes.forEach { push ->
                assertEquals("Твоје питање је одобрено", push.title)
                assertEquals("Пица или Бурек", push.body)
                assertEquals(
                    mapOf("type" to "submission_decided", "questionId" to question.id, "status" to "APPROVED"),
                    push.data,
                )
            }
        }

    @Test
    fun `a rejection is pushed with the moderator's reason`() =
        runServer("rejection") { client, google ->
            val author = client.registered("bob")
            client.registerToken(author, "bob-phone")
            val question = client.submit(author, "Летети", "Бити невидљив")

            client.post(WyrApi.Paths.ADMIN_REJECTIONS) { admin(RejectSubmissionRequest(question.id, "Већ постоји")) }

            val push = google.nextPush()
            assertEquals("bob-phone", push.token)
            assertEquals("Твоје питање није одобрено", push.title)
            assertEquals("Летети или Бити невидљив. Разлог: Већ постоји", push.body)
            assertEquals("REJECTED", push.data["status"])
        }

    @Test
    fun `a token FCM calls unregistered is forgotten and the decision stands`() =
        runServer("unregistered", fcm = { unregistered() }) { client, google ->
            val author = client.registered("cara")
            client.registerToken(author, "stale-phone")
            val question = client.submit(author, "Море", "Планина")

            val approved = client.post(WyrApi.Paths.ADMIN_APPROVALS) { admin(ApproveSubmissionRequest(question.id)) }

            assertEquals(QuestionStatus.APPROVED, approved.body<SubmissionDto>().status)
            assertEquals("stale-phone", google.nextPush().token)
            awaitTokens(emptyList())
        }

    @Test
    fun `FCM failing never fails a decision nor forgets a token`() =
        runServer("fcm-down", fcm = { respond("down", HttpStatusCode.ServiceUnavailable) }) { client, google ->
            val author = client.registered("dan")
            client.registerToken(author, "dan-phone")
            val question = client.submit(author, "Чај", "Кафа")

            val approved = client.post(WyrApi.Paths.ADMIN_APPROVALS) { admin(ApproveSubmissionRequest(question.id)) }

            assertEquals(HttpStatusCode.OK, approved.status)
            assertEquals(QuestionStatus.APPROVED, approved.body<SubmissionDto>().status)
            google.nextPush()
            assertEquals(listOf("dan-phone" to author.playerId), tokens())
        }

    @Test
    fun `with pushes off a decision calls no one`() {
        val database = testDatabaseFor("push-off")
        testApplication {
            application {
                wyrModule(testServerConfig(database, adminToken = ADMIN_TOKEN), TEST_SEEDS) {
                    fail("no engine is made for Google while pushes are off")
                }
            }
            val client = jsonClient()
            val author = client.registered("eve")
            client.registerToken(author, "eve-phone")
            val question = client.submit(author, "Зима", "Лето")

            val approved = client.post(WyrApi.Paths.ADMIN_APPROVALS) { admin(ApproveSubmissionRequest(question.id)) }

            assertEquals(HttpStatusCode.OK, approved.status)
        }
    }

    /** A client built over an engine it was handed leaves that engine running when it closes, so the server closes both. */
    @Test
    fun `stopping the server closes the engine it calls Google through`() {
        val google = FakeGoogle(fcm = { json("""{"name":"projects/$TEST_PROJECT/messages/1"}""") })
        val config =
            testServerConfig(testDatabaseFor("push-stop")).copy(fcmServiceAccount = TEST_SERVICE_ACCOUNT)

        testApplication {
            application { wyrModule(config, TEST_SEEDS) { google.engine } }
            startApplication()
            assertTrue(google.engine.isActive, "running while the server is")
        }

        assertFalse(google.engine.isActive, "closed once the server stopped")
    }

    /** One push FCM received. */
    private class Push(
        val token: String,
        val title: String,
        val body: String,
        val data: Map<String, String>,
    )

    /** Google, as a MockEngine: every token exchange granted, and every push answered as [fcm] says. */
    private class FakeGoogle(
        val fcm: MockRequestHandleScope.() -> HttpResponseData,
    ) {
        private val pushes = Channel<Push>(Channel.UNLIMITED)

        val engine =
            MockEngine { request ->
                if (request.url.toString() ==
                    TEST_TOKEN_URI
                ) {
                    return@MockEngine json("""{"access_token":"access","expires_in":3599}""")
                }
                val message =
                    Json
                        .parseToJsonElement(
                            request.body.toByteArray().decodeToString(),
                        ).jsonObject
                        .getValue("message")
                        .jsonObject
                val notification = message.getValue("notification").jsonObject
                pushes.send(
                    Push(
                        token = message.text("token"),
                        title = notification.text("title"),
                        body = notification.text("body"),
                        data =
                            message.getValue("data").jsonObject.mapValues { (_, value) ->
                                value.jsonPrimitive.content
                            },
                    ),
                )
                fcm()
            }

        /** The next push FCM receives: sent off the decision's request, not waited for by it, so waited for here. */
        suspend fun nextPush(): Push = withTimeout(10.seconds) { pushes.receive() }

        private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    }

    private var database: TestDatabaseSettings? = null

    private fun runServer(
        name: String,
        fcm: MockRequestHandleScope.() -> HttpResponseData = {
            json(
                """{"name":"projects/$TEST_PROJECT/messages/1"}""",
            )
        },
        block: suspend (HttpClient, FakeGoogle) -> Unit,
    ) = testApplication {
        val settings = testDatabaseFor("push-$name").also { database = it }
        val google = FakeGoogle(fcm)
        val config = testServerConfig(settings, adminToken = ADMIN_TOKEN).copy(fcmServiceAccount = TEST_SERVICE_ACCOUNT)
        application { wyrModule(config, TEST_SEEDS) { google.engine } }
        block(jsonClient(), google)
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient(): HttpClient =
        createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    /** Every token kept, with the player it reaches. */
    private fun tokens(): List<Pair<String, String>> =
        checkNotNull(database).serverPool().use { pool ->
            pool.inTransaction {
                PushTokens.selectAll().map { row ->
                    row[PushTokens.token] to row[PushTokens.playerId]
                }
            }
        }

    private suspend fun awaitTokens(expected: List<Pair<String, String>>) =
        withTimeout(10.seconds) {
            while (tokens() != expected) delay(20.milliseconds)
        }

    private suspend fun HttpClient.guest(): SessionDto = post(WyrApi.Paths.AUTH_GUEST).body()

    /** A guest registered as [name], with a point from an answer to pay for a submission. */
    private suspend fun HttpClient.registered(name: String): SessionDto =
        guest().also { session ->
            assertEquals(
                HttpStatusCode.OK,
                post(WyrApi.Paths.AUTH_REGISTER) {
                    json(session, RegisterRequest(name, PASSWORD))
                }.status,
            )
            post(
                WyrApi.Paths.VOTES,
            ) { json(session, VoteRequest("seed-1", OptionSide.A, UUID.randomUUID().toString())) }
        }

    private suspend fun HttpClient.login(name: String): SessionDto =
        post(WyrApi.Paths.AUTH_LOGIN) {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(name, PASSWORD))
        }.body()

    private suspend fun HttpClient.submit(
        session: SessionDto,
        optionA: String,
        optionB: String,
    ): SubmissionDto {
        val response =
            post(WyrApi.Paths.QUESTIONS) { json(session, SubmitQuestionRequest(optionA, optionB, listOf("FOOD"))) }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.registerToken(
        session: SessionDto,
        token: String,
    ): HttpResponse = post(WyrApi.Paths.MY_PUSH_TOKENS) { json(session, PushTokenRequest(token, PushPlatform.ANDROID)) }

    private suspend fun HttpClient.removeToken(
        session: SessionDto,
        token: String,
    ): HttpResponse = post(WyrApi.Paths.MY_PUSH_TOKEN_REMOVALS) { json(session, RemovePushTokenRequest(token)) }

    private companion object {
        const val ADMIN_TOKEN = "test-admin-token-long-enough-to-pass-the-length-check"
        const val PASSWORD = "a password"

        fun MockRequestHandleScope.json(
            body: String,
            status: HttpStatusCode = HttpStatusCode.OK,
        ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

        fun MockRequestHandleScope.unregistered() =
            json(
                """{"error":{"code":404,"status":"NOT_FOUND","details":[""" +
                    """{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}""",
                HttpStatusCode.NotFound,
            )

        inline fun <reified T : Any> HttpRequestBuilder.json(
            session: SessionDto,
            body: T,
        ) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        inline fun <reified T : Any> HttpRequestBuilder.admin(body: T) {
            header(WyrApi.Headers.ADMIN_TOKEN, ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
}

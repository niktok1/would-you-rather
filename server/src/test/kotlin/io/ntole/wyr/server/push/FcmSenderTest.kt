package io.ntole.wyr.server.push

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ntole.wyr.server.google.googleHttpClient
import io.ntole.wyr.server.printed
import io.ntole.wyr.server.withLogCapture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pushes through Firebase Cloud Messaging's HTTP v1 API (CLAUDE.md §8a, *Push tokens*), with Google
 * answered by a MockEngine: nothing here reaches Google.
 */
class FcmSenderTest {
    @Test
    fun `a push goes as the service account with a signed assertion and the message as FCM takes it`() =
        runBlocking {
            val google = FakeGoogle()

            val outcome = google.sender().send(DEVICE, MESSAGE)

            assertEquals(PushOutcome.SENT, outcome)
            val exchange = google.exchanges.single()
            assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", exchange["grant_type"])
            val assertion =
                JWT
                    .require(Algorithm.RSA256(TEST_KEY_PAIR.public as RSAPublicKey, null))
                    .withIssuer(TEST_CLIENT_EMAIL)
                    .withAudience(TEST_TOKEN_URI)
                    .withClaim("scope", "https://www.googleapis.com/auth/firebase.messaging")
                    .build()
                    .verify(exchange["assertion"])
            assertEquals(TEST_KEY_ID, assertion.keyId)
            assertTrue(assertion.expiresAt.time - assertion.issuedAt.time <= 3_600_000, "Google takes an hour at most")

            val send = google.sends.single()
            assertEquals("https://fcm.googleapis.com/v1/projects/$TEST_PROJECT/messages:send", send.url)
            assertEquals("Bearer access-1", send.authorization)
            val message = send.body.getValue("message").jsonObject
            assertEquals(DEVICE, message.getValue("token").jsonPrimitive.content)
            val notification = message.getValue("notification").jsonObject
            assertEquals(MESSAGE.title, notification.getValue("title").jsonPrimitive.content)
            assertEquals(MESSAGE.body, notification.getValue("body").jsonPrimitive.content)
            assertEquals(
                MESSAGE.data,
                message.getValue("data").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content },
            )
        }

    @Test
    fun `one access token serves every push until a minute before it expires`() =
        runBlocking {
            var now = 1_000_000L
            val google = FakeGoogle(expiresInSeconds = 3_600)
            val sender = google.sender(clock = { now })

            repeat(3) { assertEquals(PushOutcome.SENT, sender.send(DEVICE, MESSAGE)) }
            assertEquals(1, google.exchanges.size, "one exchange for every push within the hour")

            now += (3_600 - 59) * 1_000L
            assertEquals(PushOutcome.SENT, sender.send(DEVICE, MESSAGE))

            assertEquals(2, google.exchanges.size, "a new one within the last minute")
            assertEquals("Bearer access-2", google.sends.last().authorization)
        }

    @Test
    fun `a token FCM calls unregistered comes back UNREGISTERED`() =
        runBlocking {
            val google =
                FakeGoogle(fcm = { respondFcmError(HttpStatusCode.NotFound, "NOT_FOUND", errorCode = "UNREGISTERED") })

            assertEquals(PushOutcome.UNREGISTERED, google.sender().send(DEVICE, MESSAGE))
        }

    /** Only FCM's own UNREGISTERED forgets a token: a wrong project answers 404 too, for every token. */
    @Test
    fun `any other refusal is a failure and never a reason to forget the token`() =
        runBlocking {
            val refusals =
                listOf<MockRequestHandleScope.() -> HttpResponseData>(
                    { respondFcmError(HttpStatusCode.NotFound, "NOT_FOUND", errorCode = null) },
                    { respondFcmError(HttpStatusCode.BadRequest, "INVALID_ARGUMENT", errorCode = "INVALID_ARGUMENT") },
                    {
                        respondFcmError(
                            HttpStatusCode.Forbidden,
                            "PERMISSION_DENIED",
                            errorCode = "SENDER_ID_MISMATCH",
                        )
                    },
                    { respondFcmError(HttpStatusCode.ServiceUnavailable, "UNAVAILABLE", errorCode = "UNAVAILABLE") },
                    { respond("<html>bad gateway</html>", HttpStatusCode.BadGateway) },
                )

            refusals.forEachIndexed { index, refusal ->
                assertEquals(
                    PushOutcome.FAILED,
                    FakeGoogle(fcm = refusal).sender().send(DEVICE, MESSAGE),
                    "refusal $index",
                )
            }
        }

    @Test
    fun `an access token FCM refuses is exchanged again and the push tried once more`() =
        runBlocking {
            var sends = 0
            val google =
                FakeGoogle(fcm = {
                    if (sends++ ==
                        0
                    ) {
                        respondFcmError(HttpStatusCode.Unauthorized, "UNAUTHENTICATED", null)
                    } else {
                        respondOk()
                    }
                })

            assertEquals(PushOutcome.SENT, google.sender().send(DEVICE, MESSAGE))

            assertEquals(2, google.exchanges.size)
            assertEquals(listOf("Bearer access-1", "Bearer access-2"), google.sends.map { it.authorization })
        }

    @Test
    fun `an access token FCM refuses twice fails the push`() =
        runBlocking {
            val google = FakeGoogle(fcm = { respondFcmError(HttpStatusCode.Unauthorized, "UNAUTHENTICATED", null) })

            assertEquals(PushOutcome.FAILED, google.sender().send(DEVICE, MESSAGE))
            assertEquals(2, google.sends.size, "tried once more, and no more")
        }

    /**
     * FCM's 401 for an iOS or web device whose APNs key or web push key Firebase lacks: the access token
     * was fine, so it is kept for the next push, and the log names the code rather than the token.
     */
    @Test
    fun `a 401 naming an FCM error code fails that push alone and keeps the access token`() =
        withLogCapture { logged ->
            runBlocking {
                var sends = 0
                val google =
                    FakeGoogle(fcm = {
                        if (sends++ == 0) {
                            respondFcmError(HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "THIRD_PARTY_AUTH_ERROR")
                        } else {
                            respondOk()
                        }
                    })
                val sender = google.sender()

                assertEquals(PushOutcome.FAILED, sender.send(DEVICE, MESSAGE))
                assertEquals(1, google.exchanges.size, "no exchange for a token that was fine")
                assertEquals(1, google.sends.size, "not tried again")

                assertEquals(PushOutcome.SENT, sender.send(DEVICE, MESSAGE))
                assertEquals(1, google.exchanges.size, "the next push keeps the token")
                assertEquals(listOf("Bearer access-1", "Bearer access-1"), google.sends.map { it.authorization })

                val printed = logged.printed().joinToString("\n")
                assertTrue(printed.contains("401 UNAUTHENTICATED THIRD_PARTY_AUTH_ERROR"), printed)
                assertFalse(printed.contains("fresh access token"), printed)
            }
        }

    @Test
    fun `Google refusing the assertion fails the push and sends nothing to FCM`() =
        runBlocking {
            val google = FakeGoogle(token = { respondJson("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest) })

            assertEquals(PushOutcome.FAILED, google.sender().send(DEVICE, MESSAGE))
            assertEquals(emptyList(), google.sends)
        }

    @Test
    fun `a Google that cannot be reached fails the push`() =
        runBlocking {
            val unreachable = FakeGoogle(token = { throw IOException("connection refused") })
            val broken = FakeGoogle(fcm = { throw IOException("connection reset") })

            assertEquals(PushOutcome.FAILED, unreachable.sender().send(DEVICE, MESSAGE))
            assertEquals(PushOutcome.FAILED, broken.sender().send(DEVICE, MESSAGE))
        }

    @Test
    fun `nothing it logs holds the device token or a credential`() =
        withLogCapture { logged ->
            runBlocking {
                val cases =
                    listOf(
                        FakeGoogle(token = {
                            respondJson(
                                """{"error":"invalid_client","error_description":"$DEVICE"}""",
                                HttpStatusCode.Unauthorized,
                            )
                        }),
                        FakeGoogle(
                            fcm = {
                                respondFcmError(
                                    HttpStatusCode.BadRequest,
                                    "INVALID_ARGUMENT",
                                    "INVALID_ARGUMENT",
                                )
                            },
                        ),
                        FakeGoogle(fcm = { throw IOException("failed for $DEVICE") }),
                        FakeGoogle(fcm = { respondFcmError(HttpStatusCode.Unauthorized, "UNAUTHENTICATED", null) }),
                    )
                cases.forEach { google -> google.sender().send(DEVICE, MESSAGE) }

                val printed = logged.printed().joinToString("\n")
                assertTrue(printed.contains("FCM"), "the failures were logged: $printed")
                val secrets =
                    listOf(DEVICE, "access-1", "access-2", TEST_PRIVATE_KEY_PEM.lines()[1]) +
                        cases.flatMap { it.assertions }
                secrets.forEach { secret -> assertFalse(printed.contains(secret), "logged a secret: $printed") }
            }
        }

    /** A send FCM received: where, with which bearer, and the message. */
    private class Send(
        val url: String,
        val authorization: String?,
        val body: JsonObject,
    )

    /**
     * Google's token endpoint and FCM, answered as [token] and [fcm] say, by default granting access
     * tokens `access-1`, `access-2`... valid [expiresInSeconds], and taking every push.
     */
    private class FakeGoogle(
        val expiresInSeconds: Long = 3_599,
        val token: (MockRequestHandleScope.() -> HttpResponseData)? = null,
        val fcm: MockRequestHandleScope.() -> HttpResponseData = { respondOk() },
    ) {
        val exchanges = mutableListOf<Map<String, String?>>()
        val assertions get() = exchanges.mapNotNull { it["assertion"] }
        val sends = mutableListOf<Send>()

        val engine =
            MockEngine { request ->
                when (request.url.toString()) {
                    TEST_TOKEN_URI -> exchange(request)
                    else -> send(request)
                }
            }

        fun sender(clock: () -> Long = System::currentTimeMillis) =
            FcmSender(TEST_SERVICE_ACCOUNT, googleHttpClient(engine), clock)

        private suspend fun MockRequestHandleScope.exchange(request: HttpRequestData): HttpResponseData {
            assertEquals(HttpMethod.Post, request.method)
            val form = parseQueryString(request.body.toByteArray().decodeToString())
            exchanges += form.names().associateWith { form[it] }
            token?.let { return it() }
            return respondJson(
                """{"access_token":"access-${exchanges.size}","expires_in":$expiresInSeconds,"token_type":"Bearer"}""",
            )
        }

        private suspend fun MockRequestHandleScope.send(request: HttpRequestData): HttpResponseData {
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            sends += Send(request.url.toString(), request.headers[HttpHeaders.Authorization], body)
            return fcm()
        }
    }

    private companion object {
        const val DEVICE = "device-token-that-must-never-be-logged"
        val MESSAGE =
            PushMessage(
                title = "Твоје питање је одобрено",
                body = "Пица или бурек",
                data = mapOf("type" to SUBMISSION_DECIDED, "questionId" to "q-1", "status" to "APPROVED"),
            )

        fun MockRequestHandleScope.respondJson(
            body: String,
            status: HttpStatusCode = HttpStatusCode.OK,
        ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

        fun MockRequestHandleScope.respondOk() = respondJson("""{"name":"projects/$TEST_PROJECT/messages/0:1"}""")

        /** A refusal as FCM answers one, with FCM's own [errorCode] among the details when there is one. */
        fun MockRequestHandleScope.respondFcmError(
            status: HttpStatusCode,
            googleStatus: String,
            errorCode: String?,
        ): HttpResponseData {
            val details =
                errorCode?.let { code ->
                    """[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$code"}]"""
                }
                    ?: "[]"
            return respondJson(
                """{"error":{"code":${status.value},"message":"refused","status":"$googleStatus","details":$details}}""",
                status,
            )
        }
    }
}

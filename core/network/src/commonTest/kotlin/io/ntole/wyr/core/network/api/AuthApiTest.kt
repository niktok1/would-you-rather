package io.ntole.wyr.core.network.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.AccountDto
import io.ntole.wyr.core.auth.LoginRequest
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.ApiException
import io.ntole.wyr.core.network.BASE_URL
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.jsonHeaders
import io.ntole.wyr.core.network.respondErrorDto
import io.ntole.wyr.core.network.respondSession
import io.ntole.wyr.core.network.session
import io.ntole.wyr.core.network.storeHolding
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthApiTest {
    /** Each request as it arrived: its path and its `Authorization` header. */
    private val requests = mutableListOf<Pair<String, String?>>()

    @Test
    fun `a guest's mint is read as its session whatever else the answer carries`() =
        runTest {
            // A server that still sends a recovery secret beside the session, as d4a9dbf does.
            val minted =
                """{"playerId":"g1","accessToken":"access-g1","refreshToken":"refresh-g1",""" +
                    """"accessTokenExpiresInSeconds":900,"recoverySecret":"secret-g1"}"""
            val engine = MockEngine { respond(minted, HttpStatusCode.OK, jsonHeaders) }

            val session = AuthApi(WyrHttpClient.create(BASE_URL, storeHolding(null), engine)).guest()

            assertEquals(session("g1"), session)
        }

    @Test
    fun `a refused login comes back as it is and refreshes nothing`() =
        runTest {
            // Without the circuit breaker the bearer plugin takes this 401 for an expired access token:
            // it refreshes the guest's session and sends the login again.
            val store = storeHolding(session("guest"))
            val engine =
                MockEngine { request ->
                    requests += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                    when (request.url.encodedPath) {
                        WyrApi.Paths.AUTH_REFRESH -> respondSession(session("guest2"))
                        else -> respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.INVALID_LOGIN)
                    }
                }

            val refused =
                assertFailsWith<ApiException> {
                    AuthApi(WyrHttpClient.create(BASE_URL, store, engine)).logIn(LoginRequest("bob", "wrong!"))
                }

            assertEquals(ErrorCode.INVALID_LOGIN, refused.code)
            // Sent once, and with no bearer: a login reads none.
            assertEquals(listOf<Pair<String, String?>>(WyrApi.Paths.AUTH_LOGIN to null), requests)
            assertEquals(session("guest"), store.read(), "the guest's session is left as it was")
        }

    @Test
    fun `a login is read as the account's new session`() =
        runTest {
            var sent: LoginRequest? = null
            val engine =
                MockEngine { request ->
                    sent = WyrJson.decodeFromString<LoginRequest>(request.body.toByteArray().decodeToString())
                    respondSession(session("bob"))
                }

            val session = AuthApi(WyrHttpClient.create(BASE_URL, storeHolding(null), engine)).logIn(LOGIN)

            assertEquals(session("bob"), session)
            assertEquals(LOGIN, sent)
        }

    @Test
    fun `a registration goes with the session's bearer and is read as the account`() =
        runTest {
            var sent: RegisterRequest? = null
            val engine =
                MockEngine { request ->
                    requests += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                    sent = WyrJson.decodeFromString<RegisterRequest>(request.body.toByteArray().decodeToString())
                    respond(WyrJson.encodeToString(AccountDto("bob_1")), HttpStatusCode.OK, jsonHeaders)
                }

            val account =
                AuthApi(WyrHttpClient.create(BASE_URL, storeHolding(session("g1")), engine))
                    .register(RegisterRequest("Bob_1", "correct horse"))

            assertEquals(AccountDto("bob_1"), account)
            assertEquals(RegisterRequest("Bob_1", "correct horse"), sent)
            assertEquals(listOf<Pair<String, String?>>(WyrApi.Paths.AUTH_REGISTER to "Bearer access-g1"), requests)
        }

    @Test
    fun `a logout goes with the session's bearer and takes a 204`() =
        runTest {
            val engine =
                MockEngine { request ->
                    requests += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                    respond("", HttpStatusCode.NoContent)
                }

            AuthApi(WyrHttpClient.create(BASE_URL, storeHolding(session("bob")), engine)).logOut()

            assertEquals(listOf<Pair<String, String?>>(WyrApi.Paths.AUTH_LOGOUT to "Bearer access-bob"), requests)
        }

    private companion object {
        val LOGIN = LoginRequest("BOB", "correct horse")
    }
}

package io.ntole.wyr.core.network.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.GuestSessionDto
import io.ntole.wyr.core.auth.RecoverySecretDto
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
import kotlin.test.assertNull

/** What the recovery secret's calls send, and what their answers and refusals come back as (CLAUDE.md §8a). */
class AuthApiTest {
    @Test
    fun `a guest's mint is read with the recovery secret beside the session`() =
        runTest {
            val minted = GuestSessionDto("g1", "access-g1", "refresh-g1", 900, recoverySecret = "secret-g1")
            val engine = MockEngine { respond(WyrJson.encodeToString(minted), HttpStatusCode.OK, jsonHeaders) }

            assertEquals(minted, authApi(engine).guest())
        }

    @Test
    fun `a recovery posts the secret alone with no bearer and reads the session it opened`() =
        runTest {
            val engine = MockEngine { respondSession(session("a")) }

            // The client's store holds a session, whose bearer every other call carries.
            val recovered = authApi(engine).recover("secret-a")

            assertEquals(session("a"), recovered)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals(WyrApi.Paths.AUTH_RECOVER, sent.url.encodedPath)
            assertEquals("""{"recoverySecret":"secret-a"}""", sent.body.toByteArray().decodeToString())
            assertNull(sent.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `a refused recovery throws its code and sets off no refresh`() =
        runTest {
            // The session stored could refresh: were the 401 handed to the bearer provider, it would.
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        WyrApi.Paths.AUTH_REFRESH -> respondSession(session("a"))
                        else -> respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.INVALID_RECOVERY_SECRET)
                    }
                }

            val failure = assertFailsWith<ApiException> { authApi(engine).recover("forgotten") }

            assertEquals(ErrorCode.INVALID_RECOVERY_SECRET, failure.code)
            assertEquals(401, failure.status)
            assertEquals(listOf(WyrApi.Paths.AUTH_RECOVER), engine.requestHistory.map { it.url.encodedPath })
        }

    @Test
    fun `a new recovery secret is asked for with the bearer`() =
        runTest {
            val engine =
                MockEngine { respond(WyrJson.encodeToString(RecoverySecretDto("new")), HttpStatusCode.OK, jsonHeaders) }

            assertEquals("new", authApi(engine).newRecoverySecret().recoverySecret)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals(WyrApi.Paths.MY_RECOVERY_SECRET, sent.url.encodedPath)
            assertEquals("Bearer access-a", sent.headers[HttpHeaders.Authorization])
        }

    private fun authApi(engine: MockEngine): AuthApi =
        AuthApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine))
}

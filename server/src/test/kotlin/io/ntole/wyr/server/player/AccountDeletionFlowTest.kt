package io.ntole.wyr.server.player

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.LoginRequest
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.server.mintGuest
import io.ntole.wyr.server.runTestServer
import io.ntole.wyr.server.withLogCapture
import kotlin.test.Test
import kotlin.test.assertEquals

/** Deleting an account over HTTP (CLAUDE.md §8a, *Deleting an account*). */
class AccountDeletionFlowTest {
    @Test
    fun `a deletion is 204 and then nothing of the account works on any device`() =
        withLogCapture { logged ->
            runTestServer("account-deletion") { client, _ ->
                val phone = client.mintGuest()
                assertEquals(HttpStatusCode.OK, client.register(phone, "leaving").status)
                val tablet = client.login("leaving").body<SessionDto>()

                assertEquals(HttpStatusCode.NoContent, client.delete(phone).status)

                assertRefused(client.get(WyrApi.Paths.ME) { bearerAuth(tablet.accessToken) }, ErrorCode.UNAUTHORIZED)
                listOf(phone, tablet).forEach { session ->
                    assertRefused(client.refresh(session.refreshToken), ErrorCode.INVALID_REFRESH_TOKEN)
                }
                assertRefused(client.login("leaving"), ErrorCode.INVALID_LOGIN)
                assertRefused(client.delete(phone), ErrorCode.UNAUTHORIZED)
                assertEquals(
                    HttpStatusCode.OK,
                    client.register(client.mintGuest(), "leaving").status,
                    "the name is free",
                )
                assertEquals(
                    1,
                    logged.list.count { it.formattedMessage == "player ${phone.playerId} deleted their account" },
                )
            }
        }

    @Test
    fun `a deletion needs a session`() =
        runTestServer("account-deletion-unauthorized") { client, _ ->
            assertRefused(client.post(WyrApi.Paths.ME_DELETION), ErrorCode.UNAUTHORIZED)
        }

    private suspend fun assertRefused(
        response: HttpResponse,
        code: ErrorCode,
    ) {
        assertEquals(HttpStatusCode.Unauthorized, response.status, code.name)
        assertEquals(code, response.body<ErrorDto>().code)
    }

    private suspend fun HttpClient.delete(session: SessionDto): HttpResponse =
        post(WyrApi.Paths.ME_DELETION) { bearerAuth(session.accessToken) }

    private suspend fun HttpClient.register(
        session: SessionDto,
        username: String,
    ): HttpResponse =
        post(WyrApi.Paths.AUTH_REGISTER) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(username, PASSWORD))
        }

    private suspend fun HttpClient.login(username: String): HttpResponse =
        post(WyrApi.Paths.AUTH_LOGIN) {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, PASSWORD))
        }

    private suspend fun HttpClient.refresh(refreshToken: String): HttpResponse =
        post(WyrApi.Paths.AUTH_REFRESH) {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }

    private companion object {
        const val PASSWORD = "a password to forget"
    }
}

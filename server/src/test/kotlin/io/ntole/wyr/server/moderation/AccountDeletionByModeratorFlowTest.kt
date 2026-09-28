package io.ntole.wyr.server.moderation

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import io.ntole.wyr.core.player.DeleteAccountRequest
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.server.FLOW_ADMIN_TOKEN
import io.ntole.wyr.server.mintGuest
import io.ntole.wyr.server.printed
import io.ntole.wyr.server.runTestServer
import io.ntole.wyr.server.withLogCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A moderator deleting a player's account on their request, over HTTP (CLAUDE.md §8a, *Deleting an
 * account*): by username or by account id, the same deletion the player's own is.
 */
class AccountDeletionByModeratorFlowTest {
    @Test
    fun `a deletion by username is 204 and the account's sessions die and its name is free`() =
        withLogCapture { logged ->
            runTestServer("admin-deletion-username") { client, _ ->
                val phone = client.mintGuest()
                assertEquals(HttpStatusCode.OK, client.register(phone, USERNAME).status)
                val tablet = client.login(USERNAME).body<SessionDto>()

                val deleted = client.deleteAccount(DeleteAccountRequest(username = "  Asked_To_Go "))

                assertEquals(HttpStatusCode.NoContent, deleted.status)
                listOf(phone, tablet).forEach { session ->
                    assertRefused(client.me(session), HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                    assertRefused(client.refresh(session), HttpStatusCode.Unauthorized, ErrorCode.INVALID_REFRESH_TOKEN)
                }
                assertRefused(client.login(USERNAME), HttpStatusCode.Unauthorized, ErrorCode.INVALID_LOGIN)
                assertEquals(
                    HttpStatusCode.OK,
                    client.register(client.mintGuest(), USERNAME).status,
                    "the name is free",
                )

                val info = logged.list.filter { it.level.levelStr == "INFO" }.map { it.formattedMessage }
                assertEquals(1, info.count { it == "admin deleted the account of player ${phone.playerId}" }, "$info")
                assertFalse(logged.printed().any { USERNAME in it.lowercase() }, "no line names the username")
            }
        }

    @Test
    fun `a deletion by account id deletes a guest who has no username`() =
        runTestServer("admin-deletion-id") { client, _ ->
            val guest = client.mintGuest()
            val bystander = client.mintGuest()

            val deleted = client.deleteAccount(DeleteAccountRequest(accountId = guest.playerId))

            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertRefused(client.me(guest), HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
            assertEquals(HttpStatusCode.OK, client.me(bystander).status, "nobody else's")
            assertEquals(bystander.playerId, client.me(bystander).body<PlayerStatsDto>().playerId)
        }

    @Test
    fun `an account no player has is 404 PLAYER_NOT_FOUND and a second deletion too`() =
        runTestServer("admin-deletion-unknown") { client, _ ->
            val guest = client.mintGuest()
            assertEquals(
                HttpStatusCode.NoContent,
                client.deleteAccount(DeleteAccountRequest(accountId = guest.playerId)).status,
            )

            listOf(
                DeleteAccountRequest(accountId = guest.playerId),
                DeleteAccountRequest(accountId = "no-such-player"),
                DeleteAccountRequest(username = "nobody_here"),
                DeleteAccountRequest(username = "not a username"),
            ).forEach { request ->
                assertRefused(client.deleteAccount(request), HttpStatusCode.NotFound, ErrorCode.PLAYER_NOT_FOUND)
            }
        }

    @Test
    fun `neither or both named is 400`() =
        runTestServer("admin-deletion-malformed") { client, _ ->
            val guest = client.mintGuest()
            listOf(
                DeleteAccountRequest(),
                DeleteAccountRequest(username = USERNAME, accountId = guest.playerId),
                DeleteAccountRequest(username = " "),
                DeleteAccountRequest(accountId = ""),
            ).forEach { request ->
                assertRefused(client.deleteAccount(request), HttpStatusCode.BadRequest, ErrorCode.VALIDATION_FAILED)
            }
            assertEquals(HttpStatusCode.OK, client.me(guest).status, "nothing deleted")
        }

    @Test
    fun `a wrong or missing token is 403 and deletes nothing`() =
        runTestServer("admin-deletion-forbidden") { client, _ ->
            val guest = client.mintGuest()
            listOf("wrong-token", null).forEach { token ->
                val refused = client.deleteAccount(DeleteAccountRequest(accountId = guest.playerId), token)
                assertRefused(refused, HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN)
            }
            // A player's own bearer token is no admin token.
            val withBearer =
                client.post(WyrApi.Paths.ADMIN_ACCOUNT_DELETIONS) {
                    bearerAuth(guest.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(DeleteAccountRequest(accountId = guest.playerId))
                }
            assertRefused(withBearer, HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN)
            assertEquals(HttpStatusCode.OK, client.me(guest).status, "nothing deleted")
        }

    @Test
    fun `with moderation off the route is not there`() =
        runTestServer("admin-deletion-off", configure = { it.copy(adminToken = null) }) { client, _ ->
            val guest = client.mintGuest()

            val response = client.deleteAccount(DeleteAccountRequest(accountId = guest.playerId))

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(HttpStatusCode.OK, client.me(guest).status)
        }

    private suspend fun assertRefused(
        response: HttpResponse,
        status: HttpStatusCode,
        code: ErrorCode,
    ) {
        assertEquals(status, response.status, code.name)
        assertEquals(code, response.body<ErrorDto>().code)
    }

    private suspend fun HttpClient.deleteAccount(
        request: DeleteAccountRequest,
        token: String? = FLOW_ADMIN_TOKEN,
    ): HttpResponse =
        post(WyrApi.Paths.ADMIN_ACCOUNT_DELETIONS) {
            token?.let { header(WyrApi.Headers.ADMIN_TOKEN, it) }
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.me(session: SessionDto): HttpResponse =
        get(WyrApi.Paths.ME) { bearerAuth(session.accessToken) }

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

    private suspend fun HttpClient.refresh(session: SessionDto): HttpResponse =
        post(WyrApi.Paths.AUTH_REFRESH) {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(session.refreshToken))
        }

    private companion object {
        const val USERNAME = "asked_to_go"
        const val PASSWORD = "a password to forget"
    }
}

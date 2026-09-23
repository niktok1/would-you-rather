package io.ntole.wyr.core.data

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrJson

internal const val BASE_URL = "https://wyr.test"

/** A session whose every credential names [playerId], so a request's header says who sent it. */
internal fun session(playerId: String): SessionDto =
    SessionDto(
        playerId = playerId,
        accessToken = "access-$playerId",
        refreshToken = "refresh-$playerId",
        accessTokenExpiresInSeconds = 900,
    )

internal fun storeHolding(session: SessionDto?): SessionStore =
    SessionStore(InMemoryTokenStorage()).also { store -> session?.let(store::write) }

internal fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

/** An error exactly as the server's StatusPages renders one. */
internal fun MockRequestHandleScope.respondErrorDto(
    status: HttpStatusCode,
    code: ErrorCode,
): HttpResponseData =
    respond(
        WyrJson.encodeToString(ErrorDto(message = "test", code = code)),
        status,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

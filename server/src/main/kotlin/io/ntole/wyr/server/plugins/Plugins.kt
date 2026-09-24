package io.ntole.wyr.server.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.auth.playerId
import io.ntole.wyr.server.config.ServerConfig
import kotlinx.serialization.json.Json

/**
 * Server-side `Json`.
 *
 * `encodeDefaults = true` is the counterpart to the client's `coerceInputValues`: the client
 * relies on defaults being *present* in the payload to coerce into, so the server must not omit
 * a field just because it happens to equal its default.
 */
internal val ServerJson: Json =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

fun Application.installPlugins(
    config: ServerConfig,
    tokens: TokenService,
) {
    install(ContentNegotiation) {
        json(ServerJson)
    }

    install(CallLogging)

    install(CORS) {
        // Web is an in-scope client target, and a browser will not call the API cross-origin
        // without this. Hosts come from config so production is never wide open by default.
        config.allowedWebOrigins.forEach { origin ->
            val scheme = origin.scheme
            if (scheme == null) allowHost(origin.host) else allowHost(origin.host, schemes = listOf(scheme))
        }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        // So a moderator can work from the web client too (CLAUDE.md §8d). Allowing a browser to send
        // it grants nothing by itself: the routes still check its value, and are absent without one.
        allowHeader(WyrApi.Headers.ADMIN_TOKEN)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        // A page's script reads only the safelisted response headers and those named here, so without
        // it a browser client could never say how long a 429 asks it to wait (CLAUDE.md §8b).
        exposeHeader(HttpHeaders.RetryAfter)
    }

    install(Authentication) {
        jwt(JWT_AUTH) {
            realm = "wyr"
            // A header Ktor cannot parse ("Bearer a b") throws BadRequestException out of the default
            // authHeader, which the JWT provider does not catch, so it surfaced as a 500. Read it as no
            // credentials instead, which the challenge below answers with the usual 401.
            authHeader { call ->
                try {
                    call.request.parseAuthorizationHeader()
                } catch (_: BadRequestException) {
                    null
                }
            }
            verifier(tokens.verifier)
            validate { credential -> credential.payload.playerId()?.let { JWTPrincipal(credential.payload) } }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorDto(code = ErrorCode.UNAUTHORIZED, message = "authentication required"),
                )
            }
        }
    }

    install(StatusPages) {
        // Every deliberate failure renders as the contract's ErrorDto, so the client can branch
        // on ErrorCode instead of parsing prose or guessing from a status code.
        exception<ApiFailure> { call, failure ->
            call.respond(failure.status, ErrorDto(message = failure.message, code = failure.code))
        }
        // Ktor's rate limiter refuses with a bare 429 (installRateLimits), dressed here as the contract's
        // error like every other refusal. Its Retry-After, already on the response, is kept.
        status(HttpStatusCode.TooManyRequests) { call, status ->
            val retry = call.response.headers[HttpHeaders.RetryAfter]?.let { seconds -> "; retry in $seconds s" }
            call.respond(
                status,
                ErrorDto(code = ErrorCode.RATE_LIMITED, message = "too many requests${retry.orEmpty()}"),
            )
        }
        exception<Throwable> { call, cause ->
            // Log the detail, return none of it: an unexpected stack trace is not the client's
            // business.
            call.application.log.error("unhandled failure on ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto(code = ErrorCode.INTERNAL, message = "internal error"),
            )
        }
    }
}

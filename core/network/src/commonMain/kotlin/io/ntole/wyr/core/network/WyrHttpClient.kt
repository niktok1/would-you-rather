package io.ntole.wyr.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.network.trace.HttpTrace
import io.ntole.wyr.core.network.trace.HttpTracing
import kotlinx.coroutines.CancellationException
import io.ktor.serialization.kotlinx.json.json as jsonConverter

/**
 * Builds the app's HTTP client.
 *
 * No engine is named: exactly one engine artifact is on each platform's classpath (see this
 * module's build script), so Ktor picks it up itself and common code stays platform-free.
 */
public object WyrHttpClient {
    /**
     * @param engine for tests, which pass a `MockEngine`. Production leaves it null so Ktor
     *   discovers the platform's engine as described above.
     * @param trace where every exchange is recorded. The app passes the one the dev console reads.
     */
    public fun create(
        baseUrl: String,
        sessionStore: SessionStore,
        engine: HttpClientEngine? = null,
        trace: HttpTrace = HttpTrace(),
    ): HttpClient {
        val config: HttpClientConfig<*>.() -> Unit = {
            expectSuccess = true

            install(ContentNegotiation) {
                jsonConverter(WyrJson)
            }

            defaultRequest {
                url(baseUrl)
                contentType(ContentType.Application.Json)
            }

            install(Auth) {
                bearer {
                    // SessionStore stays the only copy of the credentials. Ktor would otherwise
                    // cache what loadTokens returned and keep sending it after the data layer
                    // replaces or clears the session — as the previous player, until rejected.
                    // Clearing that cache is no fix: in Ktor 3.5 clearToken() is deferred to a
                    // GlobalScope coroutine whenever a load or refresh holds the provider's lock,
                    // and requests in between still get the stale token. Loading per request
                    // costs one preference read.
                    cacheTokens = false

                    // The server rotates the refresh token the moment it answers a refresh. Were
                    // the caller cancelled before the write below, the store would keep a dead
                    // refresh token and the next 401 would cost the player their guest account.
                    //
                    // The cost: in Ktor 3.5 the wait for the provider's lock runs inside the same
                    // NonCancellable block, so every call queued behind a refresh is uncancellable
                    // until the refresh ends. Only each engine's own timeout bounds that. There is
                    // deliberately no HttpTimeout here: a refresh abandoned on a timeout after the
                    // server answered loses the rotated token just as a cancelled one would, and a
                    // cold start on Render's free tier can take tens of seconds.
                    nonCancellableRefresh = true

                    loadTokens { sessionStore.read()?.toBearerTokens() }

                    // Ktor calls this on a 401. Swapping the refresh token here means callers never
                    // see a transient expiry — but a failed refresh must surface, so the data layer
                    // can drop the dead session and mint a fresh guest.
                    refreshTokens {
                        val current = sessionStore.read() ?: return@refreshTokens null
                        val refreshed: SessionDto =
                            try {
                                client
                                    .post(WyrApi.Paths.AUTH_REFRESH) {
                                        markAsRefreshTokenRequest()
                                        setBody(RefreshRequest(current.refreshToken))
                                    }.body()
                            } catch (refused: ApiException) {
                                // Another client sharing this store (a second browser tab, a
                                // second desktop instance) may have spent the same refresh token
                                // first; the server rotates on use. If the store has moved on,
                                // retry as what it holds rather than report a dead session.
                                val stored = sessionStore.read()
                                if (refused.code != ErrorCode.INVALID_REFRESH_TOKEN || stored == current) throw refused
                                return@refreshTokens stored?.toBearerTokens()
                            }

                        // The data layer may have replaced or cleared the session while this was in
                        // flight. Writing now would put the old player back over the new one, so
                        // the result is dropped and the call retried as whoever is stored now.
                        // Remaining edge: a write landing between this check and the next line
                        // still loses; closing it would need a lock shared with the data layer.
                        val stored = sessionStore.read()
                        if (stored != current) return@refreshTokens stored?.toBearerTokens()

                        sessionStore.write(refreshed)
                        refreshed.toBearerTokens()
                    }
                }
            }

            // Turn every non-2xx into an ApiException that carries the server's ErrorCode, so no
            // call site has to inspect status codes or parse bodies.
            //
            // Nothing else is touched; returning lets Ktor rethrow the original. An ApiException is
            // already translated (a failed nested refresh raises one), cancellation must stay
            // cancellation (CLAUDE.md §5), and a transport failure is runApi's to call NETWORK.
            // Wrapping those too is what made offline, a dead refresh token and a cancelled call
            // all look like the same UNKNOWN.
            HttpResponseValidator {
                handleResponseExceptionWithRequest { cause, _ ->
                    if (cause is ResponseException) throw cause.toApiException()
                }
            }

            // Last, so it records each request that went out, token refreshes and retries included.
            install(HttpTracing) {
                this.trace = trace
            }
        }
        return if (engine == null) HttpClient(config) else HttpClient(engine, config)
    }
}

private fun SessionDto.toBearerTokens(): BearerTokens = BearerTokens(accessToken, refreshToken)

private suspend fun ResponseException.toApiException(): ApiException {
    val error = response.errorOrNull()
    return ApiException(
        code = error?.code ?: ErrorCode.UNKNOWN,
        status = response.status.value,
        message = error?.message ?: message,
        cause = this,
    )
}

/** The body as the server's [ErrorDto], or null when it is not one — a proxy's HTML page, say. */
private suspend fun HttpResponse.errorOrNull(): ErrorDto? =
    try {
        body<ErrorDto>()
    } catch (cancellation: CancellationException) {
        // Not "no ErrorDto": the caller went away mid-read, and that must reach it (CLAUDE.md §5).
        throw cancellation
    } catch (notAnErrorDto: Exception) {
        null
    }

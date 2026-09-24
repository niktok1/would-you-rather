package io.ntole.wyr.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import io.ktor.serialization.kotlinx.json.json as jsonConverter

/**
 * Builds the app's HTTP client.
 *
 * No engine is named: exactly one engine artifact is on each platform's classpath (see this
 * module's build script), so Ktor picks it up itself and common code stays platform-free.
 */
public object WyrHttpClient {
    /**
     * How long an ordinary call may take, from sending it to the end of its answer. A cold start on
     * Render's free tier (CLAUDE.md §8) can take most of a minute, and the player whose request
     * woke the server should get the answer, not NETWORK.
     *
     * It is the socket timeout too, the longest silence allowed between two packets, so no engine's
     * own default gives up first: OkHttp's is 10 s, and CIO's request timeout 15 s.
     */
    internal val REQUEST_TIMEOUT: Duration = 60.seconds

    /** How long opening a connection may take. Nothing has reached the server until one is open. */
    internal val CONNECT_TIMEOUT: Duration = 30.seconds

    /**
     * The request and socket timeout of a token refresh, in place of [REQUEST_TIMEOUT].
     *
     * The server rotates the refresh token as it answers a refresh (CLAUDE.md §8a), so a refresh
     * abandoned after the server ran it leaves a dead refresh token in the store, and the next 401
     * costs the player their guest account. The server runs it only once any cold start is over,
     * and then in milliseconds, so what a timeout can abandon after that is an answer still missing
     * minutes after it was sent, on a connection that has as good as died.
     *
     * Bounded all the same, and not exempt: a refresh holds the bearer provider's lock and runs
     * NonCancellable (see `nonCancellableRefresh` below), so every call rejected while it runs waits
     * behind it and cannot be cancelled. On a connection that died without a word, an unbounded
     * refresh would hold every call until the app is killed.
     *
     * Its connect timeout stays [CONNECT_TIMEOUT]: a refresh that never connected never reached the
     * server, so giving up on it cannot lose a rotation.
     */
    internal val REFRESH_TIMEOUT: Duration = 5.minutes

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

            // First, so a call's bound starts when it is made and covers a refresh and retry on its
            // way. A call waiting on a refresh cannot give up before the refresh ends (see below),
            // and fails then if its own bound has passed. A timeout fails the call with the
            // engine's or Ktor's own exception, never an ApiException, so runApi calls it NETWORK.
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds
                connectTimeoutMillis = CONNECT_TIMEOUT.inWholeMilliseconds
                socketTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds
            }

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
                    // until the refresh ends. REFRESH_TIMEOUT bounds that: a timeout abandons a
                    // refresh just as a cancellation would, so the refresh gets a bound far longer
                    // than an ordinary call's, not the same one.
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
                                        refreshTimeout()
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
                        // Remaining edge: a change landing between this check and the write below
                        // still loses. The write suspends, and on Android it commits on a thread of
                        // its own, so a change the data layer has asked for but not yet made counts
                        // too. Closing it would need a lock shared with the data layer.
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

/**
 * Gives a request that spends the refresh token [WyrHttpClient.REFRESH_TIMEOUT] in place of the
 * ordinary bound; the connect timeout is left to the client's. Every such request needs it.
 */
internal fun HttpRequestBuilder.refreshTimeout() {
    timeout {
        requestTimeoutMillis = WyrHttpClient.REFRESH_TIMEOUT.inWholeMilliseconds
        socketTimeoutMillis = WyrHttpClient.REFRESH_TIMEOUT.inWholeMilliseconds
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

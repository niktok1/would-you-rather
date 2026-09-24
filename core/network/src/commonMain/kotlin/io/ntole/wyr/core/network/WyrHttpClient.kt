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
import io.ktor.client.plugins.auth.providers.RefreshTokensParams
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
     * It is the socket timeout too, the longest silence allowed between two packets, so OkHttp's own
     * 10 s read and write timeouts do not give up first. CIO's own 15 s request timeout is no
     * concern: CIO drops it for every request once HttpTimeout is installed, whatever the values.
     */
    internal val REQUEST_TIMEOUT: Duration = 60.seconds

    /**
     * How long opening a connection may take. Nothing has reached the server until one is open.
     *
     * Only OkHttp and CIO apply it. Darwin takes the socket timeout alone, as the request's idle
     * timeout, which covers connecting too, so on iOS a connect gets [REQUEST_TIMEOUT]. The browser
     * engines apply neither, and a call there is bounded by Ktor's own request timer alone.
     */
    internal val CONNECT_TIMEOUT: Duration = 30.seconds

    /**
     * The request and socket timeout of a token refresh, in place of [REQUEST_TIMEOUT].
     *
     * The server rotates the refresh token as it answers a refresh (CLAUDE.md §8a), so a refresh
     * abandoned after the server ran it leaves in the store the token the server rotated out. The
     * server takes that token once more within its grace window, 10 minutes by default, which is why
     * this is shorter: the next call's refresh sends it again in time. Past the grace it is refused,
     * and the player becomes a fresh guest. The server runs a refresh only once any cold start is
     * over, and then in milliseconds, so what a timeout can abandon after that is an answer still
     * missing minutes after it was sent, on a connection that has as good as died.
     *
     * Bounded all the same, and not exempt: a refresh holds the bearer provider's lock and runs
     * NonCancellable (see `nonCancellableRefresh` below), so every call rejected while it runs waits
     * behind it and cannot be cancelled. On a connection that died without a word, an unbounded
     * refresh would hold every call until the app is killed. A refresh that settles a race with
     * another client (`refreshAs`) makes one more request under the same lock, bounded the same way.
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
                    // the caller cancelled before the write in refreshAs, the store would keep the
                    // token the server rotated out, which it takes only within its grace window
                    // (CLAUDE.md §8a), and a 401 after that would cost the player their guest account.
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
                        refreshAs(current, sessionStore, settleOvertaken = true)
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

/**
 * The bearer provider's refresh: spends [sent]'s refresh token, stores the session the server answers
 * with, and returns the credentials to retry the rejected call as. [settleOvertaken] allows the one
 * further refresh described below, which is itself made without it.
 */
private suspend fun RefreshTokensParams.refreshAs(
    sent: SessionDto,
    sessionStore: SessionStore,
    settleOvertaken: Boolean,
): BearerTokens? {
    val refreshed: SessionDto =
        try {
            client
                .post(WyrApi.Paths.AUTH_REFRESH) {
                    markAsRefreshTokenRequest()
                    refreshTimeout()
                    setBody(RefreshRequest(sent.refreshToken))
                }.body()
        } catch (refused: ApiException) {
            // Another client sharing this store (a second browser tab, a second desktop instance)
            // may have spent the same refresh token first; the server rotates on use, and refuses a
            // token rotated out once its grace window has passed (CLAUDE.md §8a). If the store has
            // moved on, retry as what it holds rather than report a dead session.
            val stored = sessionStore.read()
            if (refused.code != ErrorCode.INVALID_REFRESH_TOKEN || stored == sent) throw refused
            return stored?.toBearerTokens()
        }

    // The data layer may have replaced or cleared the session while this was in flight. Writing now
    // would put the old player back over the new one, so the result is dropped and the call retried
    // as whoever is stored now. Remaining edge: a change landing between this check and the write
    // below still loses. The write suspends, and on Android it commits on a thread of its own, so a
    // change the data layer has asked for but not yet made counts too, and so does another client's
    // refresh of this same session, which can then leave the store holding the token the server
    // keeps only for its grace. Closing it would need a lock shared with the data layer and across
    // processes.
    val stored = sessionStore.read()
    if (stored == sent) {
        sessionStore.write(refreshed)
        return refreshed.toBearerTokens()
    }

    // The same player in another session is another client sharing this store, which refreshed this
    // session while this refresh was in flight, and the server let both through: whichever reached it
    // second spent the token as the one the first had displaced (the grace window, CLAUDE.md §8a).
    // One of the two new refresh tokens is now the player's current one and the other only the
    // previous one, which dies with the grace, and nothing here says which is which. Keeping the
    // stored one keeps the previous one whenever the other client's refresh reached the server first,
    // as it usually does when its answer arrived first; the player's next refresh, once the access
    // token expires, well past the grace, is then refused, and they become a fresh guest. So refresh
    // once more as the stored session, which the server takes either way, and keep what that answers:
    // the latest rotation, so the current token. If that refresh fails, it fails the call as any
    // refresh does, and the store keeps the other client's session.
    if (settleOvertaken && stored != null && stored.playerId == sent.playerId) {
        return refreshAs(stored, sessionStore, settleOvertaken = false)
    }
    return stored?.toBearerTokens()
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

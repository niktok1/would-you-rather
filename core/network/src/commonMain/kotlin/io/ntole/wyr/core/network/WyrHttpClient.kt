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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
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
     * server takes that token once more, until the next rotation (its grace window), so the next
     * call's refresh can still send it; a server that bounds the grace in time must bound it longer
     * than this. But a token that was already the previous one is spent by the refresh abandoned, and
     * the next refresh with it is refused, making the player a fresh guest, which is why a refresh gets
     * far longer than an ordinary call. The server runs a refresh only once any cold start is over,
     * and then in milliseconds, so what a timeout can abandon after that is an answer still missing
     * minutes after it was sent, on a connection that has as good as died.
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
     * @param build the game's build, which every request names (CLAUDE.md §8b, *Minimum client
     *   version*), or null for a client that names none: the moderation app's, or a build that could
     *   not read its own number.
     * @param upgrade raised the first time an answer is `UPGRADE_REQUIRED`, the server refusing this
     *   build as too old, so the game can say a new version is available; null where nobody listens.
     */
    public fun create(
        baseUrl: String,
        sessionStore: SessionStore,
        engine: HttpClientEngine? = null,
        build: ClientBuild? = null,
        upgrade: UpgradeSignal? = null,
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
                // Every request, a refresh included: the server checks every path but /health.
                if (build != null) {
                    header(WyrApi.Headers.CLIENT_PLATFORM, build.platform)
                    header(WyrApi.Headers.CLIENT_VERSION, build.number.toString())
                }
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
                    // token the server rotated out, which it takes once more at most (its grace
                    // window, CLAUDE.md §8a), and not at all if it was already the previous one: the
                    // next 401 would then cost the player their guest account.
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
            //
            // An answer that this build is too old raises [upgrade] on its way: whichever call it was,
            // the game can serve nothing more until it is updated.
            HttpResponseValidator {
                handleResponseExceptionWithRequest { cause, _ ->
                    if (cause is ResponseException) {
                        val failure = cause.toApiException()
                        if (failure.code == ErrorCode.UPGRADE_REQUIRED || failure.status == HTTP_UPGRADE_REQUIRED) {
                            upgrade?.raise()
                        }
                        throw failure
                    }
                }
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
            // token it rotated out once that token has been spent again or displaced by a later
            // rotation, or its grace window is off or past a bound (CLAUDE.md §8a). If the store has
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
    // keeps only as the previous one. Closing it would need a lock shared with the data layer and
    // across processes.
    val stored = sessionStore.read()
    if (stored == sent) {
        sessionStore.write(refreshed)
        return refreshed.toBearerTokens()
    }

    // The same player in another session is another client sharing this store, which refreshed this
    // session while this refresh was in flight, and the server let both through: whichever reached it
    // second spent the token as the one the first had displaced (the grace window, CLAUDE.md §8a).
    // One of the two new refresh tokens is now the player's current one and the other only the
    // previous one, and nothing here says which is which. Keeping the stored one keeps the previous
    // one whenever the other client's refresh reached the server first, as it usually does when its
    // answer arrived first. The server takes a previous token for one refresh only: these clients
    // share an access token, so they may well refresh at once again, and of two refreshes with the
    // previous token one is refused, which replaces the player if the refusal comes before the other's
    // answer is stored (and a server that bounds the grace in time refuses it past the bound anyway).
    // So refresh once more as the stored session, which the server takes either way, and keep what
    // that answers: the latest rotation, so the current token, with which two refreshes at once both
    // go through. If that refresh fails, it fails the call as any refresh does, and the store keeps
    // the other client's session. That is worse than an ordinary failed refresh when it reached the
    // server and only its answer was lost, and the stored token was already the previous one: the
    // settling refresh spent it, so the next refresh, once the fresh access token beside it expires,
    // is refused (CLAUDE.md §8b, *Refresh answers lost past the grace*).
    if (settleOvertaken && stored != null && stored.playerId == sent.playerId) {
        return refreshAs(stored, sessionStore, settleOvertaken = false)
    }
    return stored?.toBearerTokens()
}

/** What the server answers a build older than its minimum (CLAUDE.md §8b, *Minimum client version*). */
internal const val HTTP_UPGRADE_REQUIRED: Int = 426

private fun SessionDto.toBearerTokens(): BearerTokens = BearerTokens(accessToken, refreshToken)

private suspend fun ResponseException.toApiException(): ApiException {
    val error = response.errorOrNull()
    return ApiException(
        code = error?.code ?: ErrorCode.UNKNOWN,
        status = response.status.value,
        message = error?.message ?: message,
        cause = this,
        retryAfter = response.retryAfterOrNull(),
    )
}

/**
 * The wait the response's `Retry-After` names, in whole seconds as the server sends it (CLAUDE.md
 * §8b), or null when there is none. The header's other form, an HTTP date, is null too: nothing this
 * server sends, and a date on another clock is no measure of how long to wait.
 */
private fun HttpResponse.retryAfterOrNull(): Duration? =
    headers[HttpHeaders.RetryAfter]
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.seconds

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

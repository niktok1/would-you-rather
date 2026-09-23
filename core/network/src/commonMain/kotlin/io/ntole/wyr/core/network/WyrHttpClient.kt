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
     */
    public fun create(
        baseUrl: String,
        sessionStore: SessionStore,
        engine: HttpClientEngine? = null,
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
                    loadTokens {
                        sessionStore.read()?.let { session ->
                            BearerTokens(session.accessToken, session.refreshToken)
                        }
                    }

                    // Ktor calls this on a 401. Swapping the refresh token here means callers never
                    // see a transient expiry — but a failed refresh must surface, so the data layer
                    // can drop the dead session and mint a fresh guest.
                    refreshTokens {
                        val current = sessionStore.read() ?: return@refreshTokens null
                        val refreshed: SessionDto =
                            client
                                .post(WyrApi.Paths.AUTH_REFRESH) {
                                    markAsRefreshTokenRequest()
                                    setBody(RefreshRequest(current.refreshToken))
                                }.body()
                        sessionStore.write(refreshed)
                        BearerTokens(refreshed.accessToken, refreshed.refreshToken)
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
        }
        return if (engine == null) HttpClient(config) else HttpClient(engine, config)
    }
}

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

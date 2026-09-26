package io.ntole.wyr.server.google

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The one HTTP client the server calls Google with: Firebase Cloud Messaging for pushes, and Play Games
 * for signing in (CLAUDE.md §8a). Over [engine], CIO in production (`io.ktor:ktor-client-cio`, §4) and
 * Ktor's MockEngine in every test, so no test ever reaches Google.
 *
 * A status is never an exception here (`expectSuccess` off): each caller reads Google's answer, a
 * refusal included, for what it means. Every call is bounded, so a Google that does not answer holds
 * nothing up for long: a push is sent after its decision has answered, and a sign-in waits on it.
 */
fun googleHttpClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
    }

private const val CONNECT_TIMEOUT_MILLIS = 5_000L
private const val REQUEST_TIMEOUT_MILLIS = 10_000L

/** How the server reads and writes Google's JSON: fields it does not know are skipped, and nulls left out. */
internal val GoogleJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/**
 * The `error` code of an OAuth error answer (`invalid_grant`, `invalid_client`...), or null for an answer
 * that holds none. Only the code, which names what went wrong and nothing else: never the description,
 * nor any part of what was sent, which is what a log line about a refusal may carry.
 */
internal suspend fun HttpResponse.oauthErrorCode(): String? =
    try {
        GoogleJson.decodeFromString<OAuthError>(bodyAsText()).error?.takeIf { code ->
            code.all {
                it.isLetterOrDigit() ||
                    it == '_'
            }
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

@Serializable
private class OAuthError(
    val error: String? = null,
)

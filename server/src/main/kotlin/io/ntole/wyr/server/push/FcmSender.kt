package io.ntole.wyr.server.push

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ntole.wyr.server.google.GoogleJson
import io.ntole.wyr.server.google.oauthErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.Date

/**
 * Sends pushes through Firebase Cloud Messaging's HTTP v1 API (CLAUDE.md §8a, *Push tokens*), as
 * [account], over [http] ([io.ntole.wyr.server.google.googleHttpClient]).
 *
 * Each send needs a Google access token, which the server gets as a service account does: a JWT
 * assertion signed with the account's private key (RS256, through `java-jwt`), exchanged at the
 * account's token endpoint. The token is kept until a minute before it expires, so one exchange serves
 * every push for about an hour, and pushes asking at once wait for the one exchange. A send Google
 * refuses for its access token (401 with no error code of FCM's own) gets a new one and tries once
 * more; a 401 naming one, THIRD_PARTY_AUTH_ERROR, fails that push alone and keeps the token.
 *
 * Never logged: the private key, the assertion, the access token, and the device's token. A failure is
 * logged by Google's status and error codes alone.
 */
class FcmSender(
    private val account: FcmServiceAccount,
    private val http: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : PushSender {
    private val exchange = Mutex()
    private var access: AccessToken? = null

    override suspend fun send(
        token: String,
        message: PushMessage,
    ): PushOutcome =
        try {
            when (val first = attempt(token, message)) {
                Attempt.AccessRefused -> {
                    // The access token went bad before it expired (revoked, say): a new one, once.
                    forget()
                    when (val again = attempt(token, message)) {
                        Attempt.AccessRefused -> {
                            PushOutcome.FAILED.also {
                                log.warn(
                                    "FCM refused a fresh access token",
                                )
                            }
                        }

                        is Attempt.Done -> {
                            again.outcome
                        }
                    }
                }

                is Attempt.Done -> {
                    first.outcome
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            // A timeout, a connection refused, an answer that is not JSON. The exception's message may
            // quote a URL, which names no secret, but never its body: only its class is logged.
            log.warn("a push could not reach FCM: ${failed::class.java.simpleName}")
            PushOutcome.FAILED
        }

    private suspend fun attempt(
        token: String,
        message: PushMessage,
    ): Attempt {
        val accessToken = accessToken() ?: return Attempt.Done(PushOutcome.FAILED)
        val response =
            http.post("$FCM_API/projects/${account.projectId}/messages:send") {
                bearerAuth(accessToken)
                setBody(
                    TextContent(
                        GoogleJson.encodeToString(SendRequest(message.toFcm(token))),
                        ContentType.Application.Json,
                    ),
                )
            }
        if (response.status.isSuccess()) return Attempt.Done(PushOutcome.SENT)

        val error = response.fcmError()
        // A 401 naming an error code of FCM's own is not about the access token, which then stays:
        // THIRD_PARTY_AUTH_ERROR is an iOS or web device's, whose APNs key or web push key Firebase lacks.
        if (response.status == HttpStatusCode.Unauthorized && error?.errorCode == null) return Attempt.AccessRefused
        if (error?.errorCode == UNREGISTERED) return Attempt.Done(PushOutcome.UNREGISTERED)
        log.warn(
            "FCM refused a push: ${response.status.value} ${error?.status ?: "no status"}" +
                (error?.errorCode?.let { code -> " $code" } ?: "") +
                (if (error?.errorCode == THIRD_PARTY_AUTH_ERROR) THIRD_PARTY_AUTH_HINT else ""),
        )
        return Attempt.Done(PushOutcome.FAILED)
    }

    /** The access token to send with, exchanging for a new one when there is none or it is about to expire; null when Google refused. */
    private suspend fun accessToken(): String? =
        exchange.withLock {
            access?.takeIf { it.expiresAt - EXPIRY_MARGIN_MILLIS > clock() }?.let { return it.value }
            exchangeAssertion()?.also { access = it }?.value
        }

    private suspend fun forget() = exchange.withLock { access = null }

    private suspend fun exchangeAssertion(): AccessToken? {
        val now = clock()
        val assertion =
            JWT
                .create()
                .apply { account.privateKeyId?.let(::withKeyId) }
                .withIssuer(account.clientEmail)
                .withAudience(account.tokenUri)
                .withClaim(SCOPE_CLAIM, MESSAGING_SCOPE)
                .withIssuedAt(Date(now))
                .withExpiresAt(Date(now + ASSERTION_LIFETIME_MILLIS))
                .sign(Algorithm.RSA256(null, account.privateKey))
        val response =
            http.submitForm(
                url = account.tokenUri,
                formParameters =
                    parameters {
                        append("grant_type", JWT_BEARER_GRANT)
                        append("assertion", assertion)
                    },
            )
        if (!response.status.isSuccess()) {
            log.warn(
                "Google refused FCM's access token: ${response.status.value} ${response.oauthErrorCode() ?: "no error code"}",
            )
            return null
        }
        val granted = GoogleJson.decodeFromString<TokenResponse>(response.bodyAsText())
        return AccessToken(granted.accessToken, expiresAt = now + granted.expiresIn * 1_000L)
    }

    /** What one send came to: done, as some [PushOutcome], or refused for its access token. */
    private sealed interface Attempt {
        data class Done(
            val outcome: PushOutcome,
        ) : Attempt

        data object AccessRefused : Attempt
    }

    private class AccessToken(
        val value: String,
        val expiresAt: Long,
    )

    @Serializable
    private class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long,
    )

    @Serializable
    private class SendRequest(
        val message: FcmMessage,
    )

    @Serializable
    private class FcmMessage(
        val token: String,
        val notification: FcmNotification,
        val data: Map<String, String>,
    )

    @Serializable
    private class FcmNotification(
        val title: String,
        val body: String,
    )

    private fun PushMessage.toFcm(token: String) = FcmMessage(token, FcmNotification(title, body), data)

    /** Google's error, as FCM answers a refusal: its status, and FCM's own error code among the details. */
    private class FcmError(
        val status: String?,
        val errorCode: String?,
    )

    @Serializable
    private class FcmErrorBody(
        val error: FcmErrorFields? = null,
    )

    @Serializable
    private class FcmErrorFields(
        val status: String? = null,
        val details: List<JsonObject> = emptyList(),
    )

    private suspend fun HttpResponse.fcmError(): FcmError? =
        try {
            GoogleJson.decodeFromString<FcmErrorBody>(bodyAsText()).error?.let { fields ->
                FcmError(
                    status = fields.status,
                    errorCode =
                        fields.details.firstNotNullOfOrNull { detail ->
                            detail["errorCode"]?.jsonPrimitive?.contentOrNull
                        },
                )
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        val log = LoggerFactory.getLogger(FcmSender::class.java)

        const val FCM_API = "https://fcm.googleapis.com/v1"
        const val MESSAGING_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        const val SCOPE_CLAIM = "scope"
        const val JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer"

        /** FCM's code for a token no device has any more, answered with 404. */
        const val UNREGISTERED = "UNREGISTERED"

        /** FCM's code, answered with 401, for a device Firebase has no APNs key or web push key for. */
        const val THIRD_PARTY_AUTH_ERROR = "THIRD_PARTY_AUTH_ERROR"
        const val THIRD_PARTY_AUTH_HINT =
            " (an iOS or web device: Firebase has no valid APNs key or web push key for the app)"

        /** An assertion's lifetime: Google takes an hour at most. */
        const val ASSERTION_LIFETIME_MILLIS = 60L * 60L * 1_000L

        /** How long before its expiry an access token is replaced, so none expires on its way to FCM. */
        const val EXPIRY_MARGIN_MILLIS = 60L * 1_000L
    }
}

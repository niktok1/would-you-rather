package io.ntole.wyr.core.data.session

import io.ntole.wyr.core.domain.session.RecoverySecretStatus
import io.ntole.wyr.core.domain.session.SessionDiagnostics
import io.ntole.wyr.core.domain.session.SessionInfo
import io.ntole.wyr.core.network.RecoverySecretStore
import io.ntole.wyr.core.network.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64

/**
 * Reports the stored session, and whether a recovery secret is kept, without touching the network.
 *
 * The expiry is read from the access token's own `exp` claim. The session's
 * `accessTokenExpiresInSeconds` cannot answer it: it counts from when the token was issued, and
 * that moment is not stored.
 *
 * [recovery] is null where the platform keeps no recovery secret, as for `DefaultSessionRepository`.
 */
public class DefaultSessionDiagnostics(
    private val sessionStore: SessionStore,
    private val recovery: RecoverySecretStore? = null,
) : SessionDiagnostics {
    override suspend fun info(): SessionInfo? =
        sessionStore.read()?.let { session ->
            SessionInfo(
                playerId = session.playerId,
                accessTokenExpiresAtEpochMillis = jwtExpiryEpochMillis(session.accessToken),
            )
        }

    override suspend fun recoverySecret(): RecoverySecretStatus {
        val store = recovery ?: return RecoverySecretStatus.NOT_KEPT_HERE
        return try {
            if (store.read() == null) RecoverySecretStatus.NONE else RecoverySecretStatus.KEPT
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unreadable: Exception) {
            RecoverySecretStatus.UNREADABLE
        }
    }
}

/**
 * The `exp` claim of a JWT as epoch millis, or `null` when [token] is not a JWT that carries one.
 *
 * Decoded, never verified: checking the signature is the server's job, and this is display only.
 */
internal fun jwtExpiryEpochMillis(token: String): Long? {
    val payload = token.split('.').takeIf { it.size == JWT_PARTS }?.get(1) ?: return null
    val claims =
        try {
            Json.parseToJsonElement(JwtBase64.decode(payload).decodeToString()) as? JsonObject
        } catch (malformed: IllegalArgumentException) {
            // Not base64url, or not JSON. SerializationException is an IllegalArgumentException.
            null
        }
    // A NumericDate is a JSON number of seconds; a quoted one is not.
    val seconds = (claims?.get("exp") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    return seconds?.takeIf { it in 0..MAX_EXP_SECONDS }?.times(MILLIS_PER_SECOND)
}

/** JWTs use the URL-safe alphabet and drop the padding (RFC 7515 §2). */
private val JwtBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

private const val JWT_PARTS = 3
private const val MILLIS_PER_SECOND = 1_000L
private const val MAX_EXP_SECONDS = Long.MAX_VALUE / MILLIS_PER_SECOND

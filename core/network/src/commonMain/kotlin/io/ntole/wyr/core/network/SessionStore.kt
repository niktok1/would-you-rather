package io.ntole.wyr.core.network

import io.ntole.wyr.core.auth.SessionDto
import kotlinx.serialization.json.Json

/**
 * Typed view over [TokenStorage] holding the current session.
 *
 * The whole [SessionDto] is stored as one JSON blob rather than as separate keys so a session
 * can never be half-written — a torn write would otherwise leave an access token paired with
 * someone else's refresh token.
 */
public class SessionStore(
    private val storage: TokenStorage,
    private val json: Json = WyrJson,
) {
    public fun read(): SessionDto? {
        val raw = storage.read(KEY_SESSION) ?: return null
        return runCatching { json.decodeFromString<SessionDto>(raw) }.getOrNull()
    }

    /** Returns once [session] is durable, as [TokenStorage.write] promises. */
    public suspend fun write(session: SessionDto) {
        storage.write(KEY_SESSION, json.encodeToString(session))
    }

    public suspend fun clear() {
        storage.remove(KEY_SESSION)
    }

    private companion object {
        const val KEY_SESSION = "wyr.session"
    }
}

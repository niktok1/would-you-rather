package io.ntole.wyr.core.network

import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.serialization.json.Json

/**
 * Typed view over [TokenStorage] holding the current session for [environment]'s server.
 *
 * The whole [SessionDto] is stored as one JSON blob rather than as separate keys so a session
 * can never be half-written — a torn write would otherwise leave an access token paired with
 * someone else's refresh token.
 *
 * Each environment keeps its session under a key of its own ([keyFor]), because on desktop, iOS
 * and web one storage serves every environment's build (CLAUDE.md §8e). With one key, a build for
 * one server would send another's tokens to it, have them refused, and replace that server's guest,
 * and its points with it, by a fresh one of its own.
 */
public class SessionStore(
    private val storage: TokenStorage,
    environment: WyrEnvironment,
    private val json: Json = WyrJson,
) {
    private val key = keyFor(environment)

    public fun read(): SessionDto? {
        val raw = storage.read(key) ?: return null
        return runCatching { json.decodeFromString<SessionDto>(raw) }.getOrNull()
    }

    /** Returns once [session] is durable, as [TokenStorage.write] promises. */
    public suspend fun write(session: SessionDto) {
        storage.write(key, json.encodeToString(session))
    }

    public suspend fun clear() {
        storage.remove(key)
    }

    internal companion object {
        /**
         * The key [environment]'s session is stored under. PROD keeps the one every build used before
         * there were environments, so a production guest a desktop saved then, through the retired
         * `WYR_API_BASE_URL`, is found where it was; the others each get their own.
         */
        fun keyFor(environment: WyrEnvironment): String =
            when (environment) {
                WyrEnvironment.LOCAL -> "wyr.session.local"
                WyrEnvironment.DEV -> "wyr.session.dev"
                WyrEnvironment.PROD -> "wyr.session"
            }
    }
}

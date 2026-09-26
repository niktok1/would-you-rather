package io.ntole.wyr.core.data.session

import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment

/**
 * Whether who plays on this device is settled for [environment]'s server, so a launch signs in with
 * Play Games no more (CLAUDE.md §8a, *Play Games sign-in*), kept in [storage] beside the session and
 * under a key of each environment's own, as the session is (§8e).
 *
 * [DefaultSessionRepository] keeps it: a Play Games sign-in, a login and a logout settle it, each the
 * player's own doing, and a session that died and was replaced by a fresh guest forgets it, so the
 * next launch signs that device in with Play Games again. A fresh install has none.
 */
public class PlayGamesSettled(
    private val storage: TokenStorage = InMemoryTokenStorage(),
    environment: WyrEnvironment = WyrEnvironment.LOCAL,
) {
    private val key = keyFor(environment)

    public fun isSettled(): Boolean = storage.read(key) != null

    internal suspend fun settle(): Unit = storage.write(key, SETTLED)

    internal suspend fun forget(): Unit = storage.remove(key)

    internal companion object {
        private const val SETTLED = "settled"

        fun keyFor(environment: WyrEnvironment): String = "wyr.playgames.settled.${environment.name.lowercase()}"
    }
}

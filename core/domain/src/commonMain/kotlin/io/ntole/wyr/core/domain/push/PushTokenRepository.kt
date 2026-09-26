package io.ntole.wyr.core.domain.push

/** Registers this device's push token with the game's server (CLAUDE.md §8a, *Push tokens*). */
public interface PushTokenRepository {
    /**
     * Registers [token] for [platform] under the session stored now, so its player's pushes reach this
     * device. Never mints or replaces a session: a dead one is refused, as nothing registers for it.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure.
     */
    public suspend fun register(
        token: String,
        platform: PushPlatform,
    )
}

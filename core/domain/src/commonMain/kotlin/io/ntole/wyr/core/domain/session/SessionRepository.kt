package io.ntole.wyr.core.domain.session

/**
 * The player's session.
 *
 * Zero-click by design: [ensure] mints a server-issued guest session on first run and reuses
 * the stored one afterwards, so nothing is ever asked of the player. The seam for upgrading a
 * guest into a linked provider account lives behind this interface too, which is why callers
 * only ever see a player id.
 */
public interface SessionRepository {
    /** Returns the current player id, creating a session if there isn't one yet. */
    public suspend fun ensure(): String

    /** The player id if a session already exists locally, without touching the network. */
    public suspend fun currentPlayerId(): String?

    /** Drop the local session. The next [ensure] mints a fresh guest. */
    public suspend fun clear()
}

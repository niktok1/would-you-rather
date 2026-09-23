package io.ntole.wyr.core.domain.player

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Reads the player's stats, guaranteeing a session exists first.
 *
 * The stats are the session player's, so reading them needs a session just as voting does, and on
 * a cold first launch there is none yet. Ensuring it here, as `CastVote` and `GetNextQuestion` do,
 * sends the first read with a bearer instead of having it refused and retried.
 */
public class GetPlayerStats(
    private val players: PlayerRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(): PlayerStats {
        session.ensure()
        return players.stats()
    }
}

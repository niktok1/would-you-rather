package io.ntole.wyr.core.domain.home

import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally

/**
 * Counts a tap on one of the Home screen's Play buttons (CLAUDE.md §8d, *Home picks*), once a session
 * exists: the tap is the session player's, so the session is ensured first, as the game does for a
 * vote. The game it starts ensures one anyway, and a first launch mints only one guest for both
 * (`SessionRepository.ensure`).
 */
public class PickOnHome(
    private val picks: HomePickRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(side: Side): Tally {
        session.ensure()
        return picks.pick(side)
    }
}

package io.ntole.wyr.core.domain.home

import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally

/**
 * The Home screen's two Play buttons' taps, every player's together (CLAUDE.md §8d, *Home picks*):
 * [Side.A] the button in card A's colour and [Side.B] card B's. Implemented in `:core:data`.
 *
 * The counts come as a [Tally], one tap a vote, so each button's share is worked out as a question's
 * is: [Tally.percentA] and [Tally.percentB], which always add up to 100.
 */
public interface HomePickRepository {
    /**
     * How many times each button has been tapped. Needs no session, and never makes one: the counts
     * are the same for everybody.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure.
     */
    public suspend fun counts(): Tally

    /**
     * Counts one tap of the session player's on [side]'s button, answered with both counts after it.
     * Every tap counts, a player's repeats included, and none pays or costs anything.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure.
     */
    public suspend fun pick(side: Side): Tally
}

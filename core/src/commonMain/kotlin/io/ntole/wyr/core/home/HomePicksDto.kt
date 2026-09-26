package io.ntole.wyr.core.home

import kotlinx.serialization.Serializable

/**
 * How many times each of the Home screen's two Play buttons has been tapped, by every player together
 * (CLAUDE.md §8d, *Home picks*): [picksA] the button in card A's colour, [picksB] card B's. Every tap
 * counts, a player's repeats included.
 *
 * Only the two counts travel, as for [io.ntole.wyr.core.vote.VoteTallyDto]: a share is worked out
 * where it is shown. The server reads both at one moment, so they always agree with each other. Both
 * default to 0, so a count a server stops sending reads as none rather than failing to decode.
 */
@Serializable
public data class HomePicksDto(
    public val picksA: Long = 0,
    public val picksB: Long = 0,
)

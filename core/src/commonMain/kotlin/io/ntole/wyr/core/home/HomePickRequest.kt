package io.ntole.wyr.core.home

import io.ntole.wyr.core.vote.OptionSide
import kotlinx.serialization.Serializable

/**
 * A tap on one of the Home screen's two Play buttons (CLAUDE.md §8d, *Home picks*), with a POST to
 * [io.ntole.wyr.core.api.WyrApi.Paths.HOME_PICKS]: [side] is [OptionSide.A] for the button in card A's
 * colour and [OptionSide.B] for card B's. Both start the game alike; the pick is only counted.
 *
 * Carries no player identity, for the reason [io.ntole.wyr.core.vote.VoteRequest] does not: the
 * player is whoever the request's bearer token names. [side] has no default, so a body without it asks
 * for nothing and is refused.
 */
@Serializable
public data class HomePickRequest(
    public val side: OptionSide,
)

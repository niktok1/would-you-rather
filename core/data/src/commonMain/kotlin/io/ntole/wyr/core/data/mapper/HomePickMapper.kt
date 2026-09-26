package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.home.HomePicksDto

/**
 * The Home screen's picks as a [Tally], one tap a vote (CLAUDE.md §8d, *Home picks*). A count below
 * zero, which no server sends, reads as none rather than failing the Home screen.
 */
internal fun HomePicksDto.toDomain(): Tally = Tally(votesA = picksA.coerceAtLeast(0), votesB = picksB.coerceAtLeast(0))

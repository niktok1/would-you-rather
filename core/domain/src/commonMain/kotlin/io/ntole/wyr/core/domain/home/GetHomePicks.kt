package io.ntole.wyr.core.domain.home

import io.ntole.wyr.core.domain.vote.Tally

/**
 * How many times each of the Home screen's Play buttons has been tapped (CLAUDE.md §8d, *Home picks*).
 * No session is ensured: the counts need none, so the Home screen can show them before the player has
 * one, and reading them never mints a guest.
 */
public class GetHomePicks(
    private val picks: HomePickRepository,
) {
    public suspend operator fun invoke(): Tally = picks.counts()
}

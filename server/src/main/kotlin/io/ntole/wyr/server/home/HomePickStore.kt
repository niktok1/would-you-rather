package io.ntole.wyr.server.home

import io.ntole.wyr.core.home.HomePicksDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.server.db.HomePicks
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/** The Home screen's two Play buttons, and how many times each has been tapped (CLAUDE.md §8d, *Home picks*). */
object HomePickStore {
    /**
     * Counts one tap of [side]'s button and returns both counts as they stand after it. Must run inside
     * a transaction.
     *
     * An SQL increment (`picks = picks + 1`), never a read then a write (CLAUDE.md §4): at READ
     * COMMITTED a burst of taps on one button queues on its row's lock, and each adds to the count the
     * one before committed, so none is lost. The counts are read after the increment, in this
     * transaction, so this tap is in them.
     */
    fun pick(side: OptionSide): HomePicksDto {
        val picked = HomePicks.update({ HomePicks.side eq side.name }) { row -> row[picks] = picks + 1 }
        // V15 wrote both rows and nothing deletes one.
        check(picked == 1) { "no home_picks row for side ${side.name}" }
        return counts()
    }

    /**
     * Both counts, read in one statement so they are one moment's (CLAUDE.md §4): two reads could
     * straddle a tap committing in between. Must run inside a transaction.
     */
    fun counts(): HomePicksDto {
        val bySide =
            HomePicks
                .select(HomePicks.side, HomePicks.picks)
                .associate { row -> row[HomePicks.side] to row[HomePicks.picks] }
        return HomePicksDto(picksA = bySide[OptionSide.A.name] ?: 0, picksB = bySide[OptionSide.B.name] ?: 0)
    }
}

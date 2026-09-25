package io.ntole.wyr.core.player

import kotlinx.serialization.Serializable

/**
 * The requesting player's stats, as the server counts them (CLAUDE.md §8d).
 *
 * [totalPoints] is the same running total a [io.ntole.wyr.core.vote.VoteResultDto] reports.
 * [answersGiven] counts every paid answer, re-answers included and replays not.
 * [questionsAnswered] counts the distinct questions the player has a vote on, so it is never more
 * than [answersGiven].
 *
 * [likesReceived] is how many likes the questions the player submitted hold now, the player's own
 * likes of them included (CLAUDE.md §8d). Each is a point in [totalPoints] for as long as it is held.
 *
 * [cycle] is the player's current pass over the questions, counted from 1, and [dueThisCycle] how
 * many are still due in it, over every category. A cycle ends when nothing is due, but the next one
 * starts only when the feed is next asked for a batch: in between, these report the finished cycle
 * with nothing due.
 *
 * [username] is the player's account name, lower-cased, or null for a guest, who has none
 * (CLAUDE.md §8a, *Accounts*).
 *
 * The server reads every number at the same moment, so they always agree with one another.
 *
 * The numbers have defaults, zero and the first cycle, and [username] null, so a field a server stops
 * sending reads as that rather than failing to decode.
 */
@Serializable
public data class PlayerStatsDto(
    public val playerId: String,
    public val totalPoints: Int = 0,
    public val answersGiven: Int = 0,
    public val questionsAnswered: Int = 0,
    public val cycle: Int = 1,
    public val dueThisCycle: Int = 0,
    public val likesReceived: Int = 0,
    public val username: String? = null,
)

package io.ntole.wyr.core.player

import io.ntole.wyr.core.api.WyrApi
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
 * [pointsSpent] is what the player's questions not rejected cost them to submit (CLAUDE.md §8c): a
 * rejection pays its cost back, and an approval keeps it. So [totalPoints] is what [answersGiven]
 * earned, plus what [likesReceived] earn, less [pointsSpent].
 *
 * [cycle] is the player's current pass over the questions, counted from 1, and [dueThisCycle] how
 * many are still due in it, over every category. A cycle ends when nothing is due, but the next one
 * starts only when the feed is next asked for a batch: in between, these report the finished cycle
 * with nothing due.
 *
 * [username] is the player's account name, lower-cased, or null for a guest, who has none
 * (CLAUDE.md §8a, *Accounts*). [playGamesLinked] is whether the player signed in with Play Games
 * (CLAUDE.md §8a, *Play Games sign-in*), which registers them as a username does: a player is a guest
 * while they have neither.
 *
 * [submissionCost] is what submitting a question costs on this server, in points (CLAUDE.md §8c), the
 * server's own setting, so a client says the cost the server charges rather than one it was built
 * with. It defaults to [io.ntole.wyr.core.api.WyrApi.Limits.SUBMISSION_COST], so a server from before it
 * reads as charging that, which it did.
 *
 * The server reads every number at the same moment, so they always agree with one another.
 *
 * The numbers have defaults, zero and the first cycle, [username] null and [playGamesLinked] false, so
 * a field a server stops sending reads as that rather than failing to decode.
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
    public val pointsSpent: Int = 0,
    public val username: String? = null,
    public val playGamesLinked: Boolean = false,
    public val submissionCost: Int = WyrApi.Limits.SUBMISSION_COST,
)

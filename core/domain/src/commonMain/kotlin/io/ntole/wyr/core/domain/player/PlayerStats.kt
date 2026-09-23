package io.ntole.wyr.core.domain.player

/**
 * The player's stats, as the server counts them (CLAUDE.md §8d). The client never works any of
 * them out itself.
 *
 * [totalPoints] is the running total a vote's outcome reports too. [answersGiven] counts every paid
 * answer, re-answers included and replays not, and [questionsAnswered] the distinct questions
 * answered, so it is never more than [answersGiven].
 *
 * [cycle] is the player's current pass over the questions and [dueThisCycle] how many are still
 * due in it. A finished cycle shows as nothing due until the feed is next asked for questions,
 * which is when the next one starts.
 */
public data class PlayerStats(
    public val playerId: String,
    public val totalPoints: Int,
    public val answersGiven: Int,
    public val questionsAnswered: Int,
    public val cycle: Int,
    public val dueThisCycle: Int,
)

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
 *
 * [likesReceived] is how many likes the questions the player submitted hold now, their own likes of
 * them included. Each is part of [totalPoints] while it is held, so the total can move without an
 * answer: a like of one of the player's questions, from anyone, adds to it, and an unlike takes that
 * back (CLAUDE.md §8d).
 *
 * [username] is the player's account name, lower-cased as the server keeps it, or null for a guest,
 * who has none (CLAUDE.md §8a, *Accounts*). Read with the points, so the two are one moment's.
 */
public data class PlayerStats(
    public val totalPoints: Int,
    public val answersGiven: Int,
    public val questionsAnswered: Int,
    public val cycle: Int,
    public val dueThisCycle: Int,
    public val likesReceived: Int,
    public val username: String? = null,
)

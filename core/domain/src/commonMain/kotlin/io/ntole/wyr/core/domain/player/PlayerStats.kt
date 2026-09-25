package io.ntole.wyr.core.domain.player

/**
 * The player's stats, as the server counts them (CLAUDE.md §8d). The client never works any of
 * them out itself.
 *
 * [totalPoints] is the running total a vote's outcome reports too, and [questionsAnswered] the
 * distinct questions the player has answered, however often each (CLAUDE.md §8d, *Stats*). A like of
 * one of the player's questions, from anyone, adds to the total without an answer, and taking it back
 * takes that away (§8c).
 *
 * The rest of what the server reports, the answers given, the cycle and what is due in it, the likes
 * received and the points spent, no screen shows, so it is not here: the likes and answers the
 * player's questions hold are counted by question in their list (`Submission`).
 *
 * [username] is the player's account name, lower-cased as the server keeps it, or null for a guest,
 * who has none (CLAUDE.md §8a, *Accounts*). Read with the points, so the two are one moment's.
 */
public data class PlayerStats(
    public val totalPoints: Int,
    public val questionsAnswered: Int,
    public val username: String? = null,
)

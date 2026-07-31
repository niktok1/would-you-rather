package io.ntole.wyr.server.vote

import io.ntole.wyr.core.vote.OptionSide

/**
 * The scoring rules. **Server-side only, on purpose.**
 *
 * CLAUDE.md §8b left scoring open; this is the first concrete rule set, and it lives here rather
 * than in `:core:domain` for two reasons: points must be authoritative (the client displays what
 * the server says, so it cannot recompute and disagree), and keeping it here means `:server`
 * does not need to depend on the client's domain module, leaving the §3 graph untouched.
 *
 * The rule: every vote earns [BASE_POINTS]. Picking the more popular side additionally extends a
 * streak and pays a bonus that grows with it. Picking the minority side pays only the base and
 * resets the streak to zero — so the game rewards guessing the crowd, not just playing.
 *
 * An exact tie counts as agreeing, matching `VoteOutcome.agreedWithMajority` on the client.
 */
object Scoring {
    const val BASE_POINTS: Int = 10
    const val MAJORITY_BONUS: Int = 5
    const val PER_STREAK_BONUS: Int = 2

    /** Beyond this, extra streak stops increasing the bonus. */
    const val MAX_REWARDED_STREAK: Int = 10

    /**
     * @param votesA total votes for side A **including** the vote being scored.
     * @param votesB total votes for side B **including** the vote being scored.
     * @param previousStreak the player's streak before this vote.
     */
    fun award(
        choice: OptionSide,
        votesA: Long,
        votesB: Long,
        previousStreak: Int,
    ): Award {
        val agreed =
            when {
                votesA > votesB -> choice == OptionSide.A
                votesB > votesA -> choice == OptionSide.B
                else -> true
            }

        if (!agreed) {
            return Award(points = BASE_POINTS, streak = 0, agreedWithMajority = false)
        }

        val streak = previousStreak + 1
        val bonus = MAJORITY_BONUS + minOf(streak, MAX_REWARDED_STREAK) * PER_STREAK_BONUS

        return Award(points = BASE_POINTS + bonus, streak = streak, agreedWithMajority = true)
    }

    data class Award(
        val points: Int,
        val streak: Int,
        val agreedWithMajority: Boolean,
    )
}

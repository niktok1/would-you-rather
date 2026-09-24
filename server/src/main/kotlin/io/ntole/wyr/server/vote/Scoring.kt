package io.ntole.wyr.server.vote

/**
 * The scoring rule. **Server-side only, on purpose.**
 *
 * It lives here rather than in `:core:domain` for two reasons: points must be authoritative (the
 * client displays what the server says, so it cannot recompute and disagree), and keeping it here
 * means `:server` does not need to depend on the client's domain module, leaving the §3 graph
 * untouched.
 *
 * The rule (CLAUDE.md §8d): every answer earns [POINTS_PER_ANSWER], whichever side it picks.
 * Nothing here looks at the tally. Paying more for the popular side would teach players to answer
 * what they think the crowd picks instead of what they prefer. The reveal still says whether the
 * player sided with the majority, but the client works that out from the tally, for display only.
 *
 * Every like a question holds earns its author [POINTS_PER_LIKE] for as long as it is held
 * (`LikeStore.setLiked`), their own likes included. So a player's total is always what their
 * answers earned plus that much for each like their questions hold.
 */
object Scoring {
    const val POINTS_PER_ANSWER: Int = 1

    /** Paid to a question's author when a like is added, and taken back when it is removed. */
    const val POINTS_PER_LIKE: Int = 1
}

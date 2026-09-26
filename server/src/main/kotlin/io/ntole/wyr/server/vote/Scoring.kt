package io.ntole.wyr.server.vote

import io.ntole.wyr.core.api.WyrApi

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
 * (`ReactionStore.set`), their own likes included; a dislike earns and costs nobody anything.
 * Submitting a question costs its author the server's `SUBMISSION_COST` (`ServerConfig.submissionCost`,
 * [DEFAULT_SUBMISSION_COST] unset; `SubmissionStore.submit`), which a
 * rejection pays back and an approval keeps, though an author who likes it is paid for that like as
 * for anyone's (CLAUDE.md §8c). So a player's total is always what their answers earned, plus that
 * much for each like their questions hold, less what their questions not rejected cost them.
 */
object Scoring {
    const val POINTS_PER_ANSWER: Int = 1

    /**
     * Paid to a question's author when a like is added, and taken back when it is removed, a dislike
     * replacing it included.
     */
    const val POINTS_PER_LIKE: Int = 1

    /**
     * What submitting a question costs its author (CLAUDE.md §8c) when the server's `SUBMISSION_COST`
     * is unset: 1, until the release sets 50 (§8b, *The launch*). A player needs at least the cost to
     * submit. Each question keeps what it cost (`Questions.submissionCost`), so a rejection pays back
     * what was paid, whatever the cost is by then. The wire's default ([WyrApi.Limits.SUBMISSION_COST]),
     * which a client falls back to until it has read the cost the server charges (`GET /v1/me`).
     */
    const val DEFAULT_SUBMISSION_COST: Int = WyrApi.Limits.SUBMISSION_COST
}

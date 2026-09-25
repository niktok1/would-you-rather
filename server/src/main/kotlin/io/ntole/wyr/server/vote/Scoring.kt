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
 * (`LikeStore.setLiked`), their own likes included. Submitting a question costs its author
 * [SUBMISSION_COST] (`SubmissionStore.submit`), which a rejection pays back and an approval keeps,
 * though an author who likes it is paid for that like as for anyone's (CLAUDE.md §8c, provisional). So
 * a player's total is always what their answers earned, plus that much for each like their questions
 * hold, less what their questions not rejected cost them.
 */
object Scoring {
    const val POINTS_PER_ANSWER: Int = 1

    /** Paid to a question's author when a like is added, and taken back when it is removed. */
    const val POINTS_PER_LIKE: Int = 1

    /**
     * What submitting a question costs its author (CLAUDE.md §8c), 1 until the game is released. A
     * player needs at least this much to submit. Each question keeps what it cost
     * (`Questions.submissionCost`), so a rejection pays back what was paid, whatever this is then.
     * The wire's number ([WyrApi.Limits.SUBMISSION_COST]), so a client can say what it costs.
     */
    const val SUBMISSION_COST: Int = WyrApi.Limits.SUBMISSION_COST
}

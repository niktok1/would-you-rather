package io.ntole.wyr.core.domain.vote

/**
 * Everything the result reveal screen needs after a vote lands.
 *
 * [totalPoints] and [streak] are whatever the server said they are. The client never computes
 * them — scoring is server-authoritative so the two sides cannot disagree.
 */
public data class VoteOutcome(
    public val questionId: String,
    public val yourSide: Side,
    public val tally: Tally,
    public val pointsAwarded: Int,
    public val totalPoints: Int,
    public val streak: Int,
) {
    /** True when the player picked the more popular side. A tie counts as agreeing. */
    public val agreedWithMajority: Boolean
        get() = tally.majority == null || tally.majority == yourSide
}

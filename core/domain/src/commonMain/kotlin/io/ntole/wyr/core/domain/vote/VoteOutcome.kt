package io.ntole.wyr.core.domain.vote

/**
 * Everything the result reveal screen needs after a vote lands.
 *
 * [pointsAwarded] and [totalPoints] are whatever the server said they are. The client never
 * computes them — scoring is server-authoritative so the two sides cannot disagree.
 *
 * [replayed] is true when the vote repeated the attempt the server already recorded for this
 * question (CLAUDE.md §8d, retry safety). Nothing was written: [pointsAwarded] is 0, and
 * [yourSide] is the side that attempt stored, whatever the repeat asked for.
 */
public data class VoteOutcome(
    public val questionId: String,
    public val yourSide: Side,
    public val tally: Tally,
    public val pointsAwarded: Int,
    public val totalPoints: Int,
    public val replayed: Boolean = false,
) {
    /**
     * True when the player picked the more popular side. A tie counts as agreeing.
     *
     * Display only. Points do not depend on the majority (CLAUDE.md §8d), so the server has no
     * copy of this rule to agree with.
     */
    public val agreedWithMajority: Boolean
        get() = tally.majority == null || tally.majority == yourSide
}

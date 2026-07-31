package io.ntole.wyr.core.domain.vote

/**
 * Global vote counts, plus the values derived from them.
 *
 * This is the one place totals and percentages are computed. The wire format carries only the
 * two raw counts precisely so these cannot drift out of step with them — see `VoteTallyDto`.
 */
public data class Tally(
    public val votesA: Long,
    public val votesB: Long,
) {
    init {
        require(votesA >= 0 && votesB >= 0) { "vote counts cannot be negative: $votesA/$votesB" }
    }

    public val total: Long get() = votesA + votesB

    public val hasVotes: Boolean get() = total > 0L

    /**
     * Whole-percent share of side A, rounded half-up. `0` when there are no votes at all.
     *
     * [percentB] is defined as the complement rather than rounded independently, so the two
     * always sum to exactly 100 and the UI never shows "49% / 50%".
     */
    public val percentA: Int
        get() = if (!hasVotes) 0 else ((votesA * 200L + total) / (total * 2L)).toInt()

    public val percentB: Int
        get() = if (!hasVotes) 0 else 100 - percentA

    public fun percentOf(side: Side): Int = if (side == Side.A) percentA else percentB

    public fun countOf(side: Side): Long = if (side == Side.A) votesA else votesB

    /** The more popular side, or `null` on an exact tie. */
    public val majority: Side?
        get() =
            when {
                votesA > votesB -> Side.A
                votesB > votesA -> Side.B
                else -> null
            }
}

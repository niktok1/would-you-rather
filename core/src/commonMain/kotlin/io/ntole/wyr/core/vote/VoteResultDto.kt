package io.ntole.wyr.core.vote

import kotlinx.serialization.Serializable

/**
 * Result of a cast vote, as shown on the result reveal screen.
 *
 * Self-contained by design: [yourChoice] is echoed back so a cached response still renders
 * correctly without the original request.
 *
 * [totalPoints] and [streak] are the server's authoritative running values, not deltas. The
 * client displays them rather than accumulating [pointsAwarded] itself, which would let the
 * two sides diverge.
 */
@Serializable
public data class VoteResultDto(
    public val questionId: String,
    public val yourChoice: OptionSide,
    public val tally: VoteTallyDto,
    public val pointsAwarded: Int,
    public val totalPoints: Int,
    public val streak: Int,
)

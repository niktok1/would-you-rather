package io.ntole.wyr.core.vote

import kotlinx.serialization.Serializable

/**
 * Result of a cast vote, as shown on the result reveal screen.
 *
 * Self-contained by design: [yourChoice] is echoed back so a cached response still renders
 * correctly without the original request.
 *
 * [totalPoints] is the server's authoritative running value, not a delta. The client displays
 * it rather than accumulating [pointsAwarded] itself, which would let the two sides diverge.
 *
 * [replayed] is true when the request repeated the attempt already recorded for this question
 * (see [VoteRequest.attemptId]). Nothing was written: [pointsAwarded] is 0, and [yourChoice] is the
 * side stored by that attempt, whatever the repeat asked for.
 */
@Serializable
public data class VoteResultDto(
    public val questionId: String,
    public val yourChoice: OptionSide,
    public val tally: VoteTallyDto,
    public val pointsAwarded: Int,
    public val totalPoints: Int,
    public val replayed: Boolean = false,
)

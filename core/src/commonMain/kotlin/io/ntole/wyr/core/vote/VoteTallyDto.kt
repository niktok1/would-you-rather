package io.ntole.wyr.core.vote

import kotlinx.serialization.Serializable

/**
 * Raw global vote counts for a question.
 *
 * Only the two counts are transmitted. Totals and percentages are derived at the point of
 * use so they cannot drift out of step with the counts, and the derivation lives in
 * `:core:domain` rather than here — `:core` holds no logic.
 */
@Serializable
public data class VoteTallyDto(
    public val votesA: Long,
    public val votesB: Long,
)

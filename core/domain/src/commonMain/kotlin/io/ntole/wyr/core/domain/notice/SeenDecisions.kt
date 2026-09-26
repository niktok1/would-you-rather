package io.ntole.wyr.core.domain.notice

/**
 * The decisions on [playerId]'s questions this device has shown them: the ids of their questions a
 * moderator had decided when the player last saw My questions (CLAUDE.md §8d, *Submitting*).
 */
public data class SeenDecisions(
    public val playerId: String,
    public val questionIds: Set<String>,
)

/**
 * Where [SeenDecisions] are kept on the device, for its environment's server, one player's at a time.
 * Implemented in `:core:data`.
 */
public interface SeenDecisionsStore {
    /** What was seen, or null when nothing was ever kept. */
    public fun read(): SeenDecisions?

    /** Keeps [seen] in place of what was kept. */
    public suspend fun write(seen: SeenDecisions)
}

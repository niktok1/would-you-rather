package io.ntole.wyr.core.data.notice

import io.ntole.wyr.core.domain.notice.SeenDecisions
import io.ntole.wyr.core.domain.notice.SeenDecisionsStore
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment

/**
 * The decisions a player has seen (CLAUDE.md §8d, *Submitting*), kept in [storage], the session's, under
 * a key of each environment's own, as the session is (§8e): `wyr.decisions.seen.local`, `.dev` or
 * `.prod`. One player's at a time, their id on the first line and a question's id on each after it:
 * neither holds a line break.
 */
public class StoredSeenDecisions(
    private val storage: TokenStorage,
    environment: WyrEnvironment,
) : SeenDecisionsStore {
    private val key = keyFor(environment)

    override fun read(): SeenDecisions? {
        val lines = storage.read(key)?.lines() ?: return null
        val player = lines.first().takeIf { it.isNotEmpty() } ?: return null
        return SeenDecisions(player, lines.drop(1).filter { it.isNotEmpty() }.toSet())
    }

    override suspend fun write(seen: SeenDecisions) {
        storage.write(key, (listOf(seen.playerId) + seen.questionIds.sorted()).joinToString("\n"))
    }

    internal companion object {
        fun keyFor(environment: WyrEnvironment): String = "wyr.decisions.seen.${environment.name.lowercase()}"
    }
}

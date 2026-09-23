package io.ntole.wyr.core.data.player

import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.withSessionRecovery
import io.ntole.wyr.core.domain.player.PlayerRepository
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.network.api.PlayerApi

/**
 * Reads the player's stats, recovering once from a session the server has stopped accepting (see
 * [withSessionRecovery]). A recovered session is a fresh guest, so the stats that come back are
 * that guest's, not the dead session's.
 */
public class DefaultPlayerRepository(
    private val api: PlayerApi,
    private val session: DefaultSessionRepository,
) : PlayerRepository {
    override suspend fun stats(): PlayerStats = session.withSessionRecovery { api.me() }.toDomain()
}

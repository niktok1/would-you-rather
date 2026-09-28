package io.ntole.wyr.core.data.home

import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.data.mapper.toWire
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.withSessionRecovery
import io.ntole.wyr.core.domain.home.HomePickRepository
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.home.HomePickRequest
import io.ntole.wyr.core.network.api.HomePickApi

/**
 * Reads the Home screen's picks through [runApi] alone, as the categories are read: the counts need no
 * session, so there is none to recover and a read never mints a guest. A tap goes through
 * [withSessionRecovery], as a vote does, sent again as the fresh guest on a dead session: the 401
 * counted nothing, so the tap counts once.
 */
public class DefaultHomePickRepository(
    private val api: HomePickApi,
    private val session: DefaultSessionRepository,
) : HomePickRepository {
    override suspend fun counts(): Tally = runApi { api.counts() }.toDomain()

    override suspend fun pick(side: Side): Tally =
        session.withSessionRecovery { api.pick(HomePickRequest(side.toWire())) }.toDomain()
}

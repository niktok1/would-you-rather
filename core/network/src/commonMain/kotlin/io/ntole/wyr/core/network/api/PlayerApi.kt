package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.player.PlayerStatsDto

public class PlayerApi(
    private val client: HttpClient,
) {
    /** The session player's stats. Requires a session: the Auth plugin attaches the bearer token. */
    public suspend fun me(): PlayerStatsDto = client.get(WyrApi.Paths.ME).body()
}

package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.home.HomePickRequest
import io.ntole.wyr.core.home.HomePicksDto

/** The Home screen's two Play buttons' taps (CLAUDE.md §8d, *Home picks*). */
public class HomePickApi(
    private val client: HttpClient,
) {
    /**
     * Both counts. Needs no session: with none stored the Auth plugin sends no bearer, and the server
     * reads none. A refusal throws, as every non-2xx does.
     */
    public suspend fun counts(): HomePicksDto = client.get(WyrApi.Paths.HOME_PICKS).body()

    /** Counts one tap of the session player's, answered with both counts after it. Requires a session. */
    public suspend fun pick(request: HomePickRequest): HomePicksDto =
        client
            .post(WyrApi.Paths.HOME_PICKS) {
                setBody(request)
            }.body()
}

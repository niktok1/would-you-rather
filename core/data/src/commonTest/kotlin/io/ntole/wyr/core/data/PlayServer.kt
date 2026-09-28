package io.ntole.wyr.core.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.home.HomePickRequest
import io.ntole.wyr.core.home.HomePicksDto
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.shop.PurchaseRequest
import io.ntole.wyr.core.shop.ShopDto
import io.ntole.wyr.core.vote.OptionSide
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Just enough of the server, behind a [MockEngine], for the Play screen's menu, the Home screen's
 * picks and the shop (CLAUDE.md §8d, *Reports*, *Home picks*, *The shop*): it mints guests, refuses
 * every refresh, as for a player the server has never heard of, and answers a report, a hide, a pick,
 * a read of the shop and a purchase from a player it minted, 401 from any other. The picks' counts it reads to anybody. Apart from [FakeServer], whose
 * routes are the rest of the game's.
 */
internal class PlayServer {
    private val lock = Mutex()
    private val players = mutableSetOf<String>()

    var guestsMinted = 0
        private set

    /** The path, `Authorization` header and body of every report and hide, in arrival order. */
    val sent = mutableListOf<Triple<String, String?, String>>()

    /** When set, every report and hide is refused with this status and code, whoever sends it. */
    var refuseWith: Pair<HttpStatusCode, ErrorCode>? = null

    /** What the Home screen's picks stand at: every tap on each button. */
    var picks = HomePicksDto(picksA = 0, picksB = 0)

    /** When set, every read of the picks is refused with this status and code. */
    var refusePickReadsWith: Pair<HttpStatusCode, ErrorCode>? = null

    /** The `Authorization` header of every read of the picks, in arrival order. */
    val pickReadsSentAs = mutableListOf<String?>()

    /** The `Authorization` header and side of every pick, in arrival order. */
    val picksSentAs = mutableListOf<Pair<String?, OptionSide>>()

    /** What the shop answers with, a read and a purchase alike. */
    var shop = ShopDto()

    /** When set, every purchase is refused with this status and code, whoever sends it. */
    var refusePurchasesWith: Pair<HttpStatusCode, ErrorCode>? = null

    /** The `Authorization` header and item of every purchase, in arrival order. */
    val purchasesSentAs = mutableListOf<Pair<String?, String>>()

    val engine = MockEngine { request -> lock.withLock { handle(request) } }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val authorization = request.headers[HttpHeaders.Authorization]
        val known = authorization?.removePrefix("Bearer access-") in players
        return when (val path = request.url.encodedPath) {
            WyrApi.Paths.AUTH_GUEST -> {
                guestsMinted++
                val player = "guest$guestsMinted"
                players += player
                respondJson(WyrJson.encodeToString(sessionOf(player)))
            }

            WyrApi.Paths.AUTH_REFRESH -> {
                respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.INVALID_REFRESH_TOKEN)
            }

            WyrApi.Paths.REPORTS, WyrApi.Paths.HIDDEN_QUESTIONS, WyrApi.Paths.HIDDEN_AUTHORS -> {
                sent += Triple(path, authorization, request.body.toByteArray().decodeToString())
                val refusal = refuseWith
                when {
                    refusal != null -> respondErrorDto(refusal.first, refusal.second)
                    !known -> respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                    else -> respond("", HttpStatusCode.NoContent)
                }
            }

            WyrApi.Paths.HOME_PICKS -> {
                if (request.method == HttpMethod.Post) pick(request, authorization, known) else readPicks(authorization)
            }

            WyrApi.Paths.SHOP -> {
                if (known) respondJson(WyrJson.encodeToString(shop)) else unknown()
            }

            WyrApi.Paths.MY_PURCHASES -> {
                val item = WyrJson.decodeFromString<PurchaseRequest>(request.body.toByteArray().decodeToString()).itemId
                purchasesSentAs += authorization to item
                val refusal = refusePurchasesWith
                when {
                    !known -> unknown()
                    refusal != null -> respondErrorDto(refusal.first, refusal.second)
                    else -> respondJson(WyrJson.encodeToString(shop))
                }
            }

            else -> {
                error("PlayServer has no route for ${request.url}")
            }
        }
    }

    private fun MockRequestHandleScope.readPicks(authorization: String?): HttpResponseData {
        pickReadsSentAs += authorization
        val refusal = refusePickReadsWith
        return if (refusal != null) {
            respondErrorDto(refusal.first, refusal.second)
        } else {
            respondJson(WyrJson.encodeToString(picks))
        }
    }

    private suspend fun MockRequestHandleScope.pick(
        request: HttpRequestData,
        authorization: String?,
        known: Boolean,
    ): HttpResponseData {
        val side = WyrJson.decodeFromString<HomePickRequest>(request.body.toByteArray().decodeToString()).side
        picksSentAs += authorization to side
        if (!known) return respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
        picks =
            when (side) {
                OptionSide.A -> picks.copy(picksA = picks.picksA + 1)
                OptionSide.B -> picks.copy(picksB = picks.picksB + 1)
            }
        return respondJson(WyrJson.encodeToString(picks))
    }

    private fun MockRequestHandleScope.unknown(): HttpResponseData =
        respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)

    private fun sessionOf(player: String): SessionDto =
        SessionDto(
            playerId = player,
            accessToken = "access-$player",
            refreshToken = "refresh-$player",
            accessTokenExpiresInSeconds = 900,
        )
}

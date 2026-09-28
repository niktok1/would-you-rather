package io.ntole.wyr.server.shop

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.shop.PurchaseRequest
import io.ntole.wyr.core.shop.ShopDto
import io.ntole.wyr.core.shop.ShopThemeDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.mintGuest
import io.ntole.wyr.server.runTestServer
import io.ntole.wyr.server.vote.Scoring
import io.ntole.wyr.server.withLogCapture
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The shop end to end (CLAUDE.md §8d, *The shop*), at the server's `THEME_PRICE`. */
class ShopFlowTest {
    @Test
    fun `the shop lists every theme unowned at the server's price, with the player's points`() =
        runTestServer("shop-list", configure = { it.copy(themePrice = PRICE) }) { client, _ ->
            val guest = client.mintGuest()
            repeat(2) { client.answer(guest) }

            val response = client.get(WyrApi.Paths.SHOP) { bearerAuth(guest.accessToken) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                ShopDto(
                    themes = listOf("NEON_NIGHT", "OCEAN", "FOREST", "SUNSET").map { ShopThemeDto(it, PRICE) },
                    totalPoints = 2,
                    registered = false,
                ),
                response.body(),
            )
            assertTrue(client.shop(client.registered("lister")).registered, "a username registers a player")
        }

    @Test
    fun `a server with no price set sells at 220`() =
        runTestServer("shop-default-price") { client, _ ->
            val shop = client.shop(client.mintGuest())

            assertEquals(setOf(ShopCatalog.DEFAULT_THEME_PRICE), shop.themes.map { it.price }.toSet())
        }

    @Test
    fun `a guest may not buy, and is told so before anything they sent is checked`() =
        runTestServer("shop-guest", configure = { it.copy(themePrice = 0) }) { client, _ ->
            val guest = client.mintGuest()

            for (itemId in listOf("OCEAN", "not an id")) {
                val refused = client.buy(guest, itemId)
                assertEquals(HttpStatusCode.Forbidden, refused.status, itemId)
                assertEquals(ErrorCode.ACCOUNT_REQUIRED, refused.body<ErrorDto>().code, itemId)
            }
            assertTrue(client.shop(guest).themes.none { it.owned })
        }

    @Test
    fun `a purchase takes exactly the price, and the stats still add up`() =
        withLogCapture { logged ->
            runTestServer("shop-buy", configure = { it.copy(themePrice = PRICE) }) { client, _ ->
                val buyer = client.registered("buyer")
                repeat(PRICE - 1) { client.answer(buyer) }

                val tooFew = client.buy(buyer, "OCEAN")
                assertEquals(HttpStatusCode.Conflict, tooFew.status)
                assertEquals(ErrorCode.NOT_ENOUGH_POINTS, tooFew.body<ErrorDto>().code)
                assertEquals(PRICE - 1, client.shop(buyer).totalPoints, "a refusal takes nothing")

                repeat(2) { client.answer(buyer) }
                val bought = client.buy(buyer, "OCEAN")
                assertEquals(HttpStatusCode.OK, bought.status)
                val shop = bought.body<ShopDto>()
                assertEquals(1, shop.totalPoints, "the price taken, and nothing more")
                assertEquals(listOf("OCEAN"), shop.themes.filter { it.owned }.map { it.id })
                assertTrue(shop.registered)
                assertEquals(shop, client.shop(buyer), "the answer is the shop as it stands")

                val again = client.buy(buyer, "OCEAN")
                assertEquals(HttpStatusCode.Conflict, again.status)
                assertEquals(ErrorCode.ALREADY_OWNED, again.body<ErrorDto>().code)
                assertEquals(1, client.shop(buyer).totalPoints, "owned already takes nothing")

                val stats = client.stats(buyer)
                assertEquals(PRICE, stats.pointsSpent)
                assertEquals(
                    stats.answersGiven * Scoring.POINTS_PER_ANSWER + stats.likesReceived * Scoring.POINTS_PER_LIKE -
                        stats.pointsSpent,
                    stats.totalPoints,
                    "what the answers earned, and the likes, less what was spent",
                )

                val info = logged.list.filter { it.level.levelStr == "INFO" }.map { it.formattedMessage }
                assertEquals(1, info.count { it == "player ${buyer.playerId} bought OCEAN" }, "one line, of ids")
                assertFalse(info.any { "buyer" in it && "bought" in it }, "never the username")
            }
        }

    @Test
    fun `an id the shop does not sell is 404, and one no item could have 400`() =
        runTestServer("shop-unknown", configure = { it.copy(themePrice = 0) }) { client, _ ->
            val buyer = client.registered("seeker")

            val unknown = client.buy(buyer, "RAINBOW")
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals(ErrorCode.ITEM_NOT_FOUND, unknown.body<ErrorDto>().code)

            for (itemId in listOf("", "ocean", "OCEAN ", "A".repeat(33), "NEON-NIGHT")) {
                val malformed = client.buy(buyer, itemId)
                assertEquals(HttpStatusCode.BadRequest, malformed.status, itemId)
                assertEquals(ErrorCode.VALIDATION_FAILED, malformed.body<ErrorDto>().code, itemId)
            }
            val noBody =
                client.post(WyrApi.Paths.MY_PURCHASES) {
                    bearerAuth(buyer.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.BadRequest, noBody.status)
            assertTrue(client.shop(buyer).themes.none { it.owned })
        }

    @Test
    fun `both routes need a session`() =
        runTestServer("shop-no-session") { client, _ ->
            val read = client.get(WyrApi.Paths.SHOP)
            assertEquals(HttpStatusCode.Unauthorized, read.status)
            assertEquals(ErrorCode.UNAUTHORIZED, read.body<ErrorDto>().code)

            val bought =
                client.post(WyrApi.Paths.MY_PURCHASES) {
                    contentType(ContentType.Application.Json)
                    setBody(PurchaseRequest("OCEAN"))
                }
            assertEquals(HttpStatusCode.Unauthorized, bought.status)
            assertEquals(ErrorCode.UNAUTHORIZED, bought.body<ErrorDto>().code)
        }

    @Test
    fun `a price of 0 lets a player with no points buy`() =
        runTestServer("shop-free", configure = { it.copy(themePrice = 0) }) { client, _ ->
            val buyer = client.registered("free")

            val bought = client.buy(buyer, "SUNSET")

            assertEquals(HttpStatusCode.OK, bought.status)
            assertEquals(0, bought.body<ShopDto>().totalPoints)
            assertEquals(0, client.stats(buyer).pointsSpent)
        }

    private suspend fun HttpClient.registered(username: String): SessionDto {
        val guest = mintGuest()
        val registered =
            post(WyrApi.Paths.AUTH_REGISTER) {
                bearerAuth(guest.accessToken)
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest(username, "a password"))
            }
        assertEquals(HttpStatusCode.OK, registered.status)
        return guest
    }

    /** One more answer, a point, whichever side: a re-answer pays every time (CLAUDE.md §8d). */
    private suspend fun HttpClient.answer(session: SessionDto) {
        val answered =
            post(WyrApi.Paths.VOTES) {
                bearerAuth(session.accessToken)
                contentType(ContentType.Application.Json)
                setBody(VoteRequest("seed-1", OptionSide.A, UUID.randomUUID().toString()))
            }
        assertEquals(HttpStatusCode.OK, answered.status)
    }

    private suspend fun HttpClient.buy(
        session: SessionDto,
        itemId: String,
    ): HttpResponse =
        post(WyrApi.Paths.MY_PURCHASES) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PurchaseRequest(itemId))
        }

    private suspend fun HttpClient.shop(session: SessionDto): ShopDto =
        get(WyrApi.Paths.SHOP) { bearerAuth(session.accessToken) }.body()

    private suspend fun HttpClient.stats(session: SessionDto): PlayerStatsDto =
        get(WyrApi.Paths.ME) { bearerAuth(session.accessToken) }.body()

    private companion object {
        const val PRICE = 3
    }
}

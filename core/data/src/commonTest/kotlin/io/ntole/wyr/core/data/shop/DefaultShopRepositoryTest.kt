package io.ntole.wyr.core.data.shop

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.PlayServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.shop.Shop
import io.ntole.wyr.core.domain.shop.ShopTheme
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.ShopApi
import io.ntole.wyr.core.shop.ShopDto
import io.ntole.wyr.core.shop.ShopThemeDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The shop through the real client (CLAUDE.md §8d, *The shop*). */
class DefaultShopRepositoryTest {
    private val server = PlayServer()

    @Test
    fun `the shop is read as the domain holds it`() =
        runTest {
            val store = storeHolding(null)
            server.shop =
                ShopDto(
                    themes = listOf(ShopThemeDto("OCEAN", price = 220, owned = true), ShopThemeDto("FOREST", 220)),
                    totalPoints = 43,
                    registered = true,
                )
            val sessions = sessionsOver(store)
            sessions.ensure()

            val shop = repositoryOver(store).shop()

            assertEquals(
                Shop(
                    themes = listOf(ShopTheme("OCEAN", 220, owned = true), ShopTheme("FOREST", 220, owned = false)),
                    points = 43,
                    registered = true,
                ),
                shop,
            )
        }

    @Test
    fun `a purchase names the theme and answers the shop after it`() =
        runTest {
            val store = storeHolding(null)
            sessionsOver(store).ensure()
            server.shop = ShopDto(themes = listOf(ShopThemeDto("OCEAN", price = 220, owned = true)), totalPoints = 0)

            val shop = repositoryOver(store).buy("OCEAN")

            assertEquals(listOf<Pair<String?, String>>("Bearer access-guest1" to "OCEAN"), server.purchasesSentAs)
            assertEquals(true, shop.theme("OCEAN")?.owned)
        }

    @Test
    fun `each refusal of a purchase is the domain's own`() =
        runTest {
            val store = storeHolding(null)
            sessionsOver(store).ensure()
            listOf(
                HttpStatusCode.Conflict to ErrorCode.ALREADY_OWNED to DomainError.ALREADY_OWNED,
                HttpStatusCode.NotFound to ErrorCode.ITEM_NOT_FOUND to DomainError.ITEM_NOT_FOUND,
                HttpStatusCode.Conflict to ErrorCode.NOT_ENOUGH_POINTS to DomainError.NOT_ENOUGH_POINTS,
                HttpStatusCode.Forbidden to ErrorCode.ACCOUNT_REQUIRED to DomainError.ACCOUNT_REQUIRED,
            ).forEach { (refusal, expected) ->
                server.refusePurchasesWith = refusal

                val failure = assertFailsWith<WyrException> { repositoryOver(store).buy("OCEAN") }

                assertEquals(expected, failure.error)
            }
            assertEquals("guest1", store.read()?.playerId, "none of them touches the session")
        }

    @Test
    fun `a purchase on a dead session is sent again as a fresh guest`() =
        runTest {
            // The server has never heard of "a": the state after a dev server restarts.
            val store = storeHolding(session("a"))

            repositoryOver(store).buy("SUNSET")

            assertEquals(
                listOf<Pair<String?, String>>("Bearer access-a" to "SUNSET", "Bearer access-guest1" to "SUNSET"),
                server.purchasesSentAs,
            )
        }

    private fun sessionsOver(store: SessionStore): DefaultSessionRepository {
        val client = WyrHttpClient.create(BASE_URL, store, server.engine)
        return DefaultSessionRepository(AuthApi(client), store)
    }

    private fun repositoryOver(store: SessionStore): DefaultShopRepository {
        val client = WyrHttpClient.create(BASE_URL, store, server.engine)
        return DefaultShopRepository(ShopApi(client), DefaultSessionRepository(AuthApi(client), store))
    }
}

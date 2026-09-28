package io.ntole.wyr.shop

import io.ntole.wyr.analytics.RecordingAnalytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.shop.BuyTheme
import io.ntole.wyr.core.domain.shop.GetShop
import io.ntole.wyr.core.domain.shop.Shop
import io.ntole.wyr.core.domain.shop.ShopRepository
import io.ntole.wyr.core.domain.shop.ShopTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shop, over a scripted repository (CLAUDE.md §8d, *The shop*). */
@OptIn(ExperimentalCoroutinesApi::class)
class ShopViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val analytics = RecordingAnalytics()
    private val shops = ScriptedShop()

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which has no implementation under test.
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the shop is read each time it is shown and a visit is counted once`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.shown()
            testScheduler.advanceUntilIdle()
            viewModel.shown(newVisit = false)
            testScheduler.advanceUntilIdle()

            assertEquals(SHOP, viewModel.state.value.shop)
            assertEquals(2, shops.reads)
            assertEquals(1, analytics.named(AnalyticsEvent.SHOP_OPENED).size)
        }

    @Test
    fun `a theme is bought only once the dialog confirms it and the shop after it shows`() =
        runTest(dispatcher) {
            val viewModel = shownViewModel()

            viewModel.askToBuy("OCEAN")
            assertEquals("OCEAN", viewModel.state.value.confirming)
            assertEquals(emptyList(), shops.bought, "nothing bought before the dialog confirms it")

            viewModel.buy()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(listOf("OCEAN"), shops.bought)
            assertNull(state.confirming)
            assertEquals(true, state.shop?.theme("OCEAN")?.owned)
            assertEquals(0, state.shop?.points)
            assertEquals("OCEAN", state.bought, "for the screen to put it on")
            val bought = analytics.named(AnalyticsEvent.THEME_BOUGHT).single()
            assertEquals("OCEAN", bought.properties[AnalyticsProperty.THEME])
            assertEquals(220, bought.properties[AnalyticsProperty.PRICE])

            viewModel.boughtWorn()
            assertNull(viewModel.state.value.bought)
        }

    @Test
    fun `cancelling the dialog buys nothing`() =
        runTest(dispatcher) {
            val viewModel = shownViewModel()

            viewModel.askToBuy("OCEAN")
            viewModel.cancelBuy()
            viewModel.buy()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), shops.bought)
            assertNull(viewModel.state.value.confirming)
        }

    @Test
    fun `no dialog opens for a guest or for too few points or for a theme owned`() =
        runTest(dispatcher) {
            listOf(
                SHOP.copy(registered = false),
                SHOP.copy(points = 219),
                SHOP.copy(themes = SHOP.themes.map { it.copy(owned = true) }),
            ).forEach { shop ->
                shops.shop = shop
                val viewModel = shownViewModel()

                viewModel.askToBuy("OCEAN")

                assertNull(viewModel.state.value.confirming, "$shop")
            }
            assertTrue(SHOP.copy(points = 219).let { ShopState(shop = it).tooFewPoints })
            assertFalse(
                ShopState(shop = SHOP.copy(registered = false, points = 0)).tooFewPoints,
                "a guest is told to register",
            )
            assertTrue(ShopState(shop = SHOP.copy(registered = false)).isGuest)
        }

    @Test
    fun `a purchase refused says why and reads the shop again`() =
        runTest(dispatcher) {
            val viewModel = shownViewModel()
            shops.refuseWith = DomainError.ALREADY_OWNED
            shops.shop = SHOP.copy(themes = SHOP.themes.map { it.copy(owned = true) })

            viewModel.askToBuy("OCEAN")
            viewModel.buy()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(DomainError.ALREADY_OWNED, state.buyFailure?.error)
            assertEquals(true, state.shop?.theme("OCEAN")?.owned, "the shop read again")
            assertNull(state.bought)
            val shown = analytics.named(AnalyticsEvent.ERROR_SHOWN).single()
            assertEquals("buy_theme", shown.properties[AnalyticsProperty.ACTION])
        }

    @Test
    fun `a read that fails says why and keeps what was shown`() =
        runTest(dispatcher) {
            val viewModel = shownViewModel()
            shops.failReadsWith = DomainError.NETWORK

            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            assertEquals(
                DomainError.NETWORK,
                viewModel.state.value.readFailure
                    ?.error,
            )
            assertEquals(SHOP, viewModel.state.value.shop)
        }

    private fun TestScope.shownViewModel(): ShopViewModel {
        val viewModel = viewModel()
        viewModel.shown()
        testScheduler.advanceUntilIdle()
        return viewModel
    }

    private fun viewModel(): ShopViewModel {
        val session = NoSession()
        return ShopViewModel(GetShop(shops, session), BuyTheme(shops, session), analytics)
    }

    private class ScriptedShop : ShopRepository {
        var shop = SHOP
        var reads = 0
        val bought = mutableListOf<String>()
        var refuseWith: DomainError? = null
        var failReadsWith: DomainError? = null

        override suspend fun shop(): Shop {
            reads++
            failReadsWith?.let { throw WyrException(it) }
            return shop
        }

        override suspend fun buy(themeId: String): Shop {
            refuseWith?.let { throw WyrException(it) }
            bought += themeId
            shop =
                shop.copy(themes = shop.themes.map { if (it.id == themeId) it.copy(owned = true) else it }, points = 0)
            return shop
        }
    }

    private class NoSession : SessionRepository {
        override suspend fun ensure(): String = "p1"
    }

    private companion object {
        val SHOP =
            Shop(
                themes = listOf(ShopTheme("NEON_NIGHT", 220, owned = true), ShopTheme("OCEAN", 220, owned = false)),
                points = 220,
                registered = true,
            )
    }
}

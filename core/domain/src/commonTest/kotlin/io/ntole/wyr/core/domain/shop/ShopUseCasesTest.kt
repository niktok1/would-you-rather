package io.ntole.wyr.core.domain.shop

import io.ntole.wyr.core.domain.session.SessionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shop (CLAUDE.md §8d, *The shop*): what is owned is the player's, so both ensure a session first. */
class ShopUseCasesTest {
    private val calls = mutableListOf<String>()
    private val shops = RecordingShop(calls)
    private val session = RecordingSessions(calls)

    @Test
    fun `the shop is read once a session is ensured`() =
        runTest {
            val shop = GetShop(shops, session)()

            assertEquals(SHOP, shop)
            assertEquals(listOf("ensure", "shop"), calls)
        }

    @Test
    fun `a theme is bought once a session is ensured`() =
        runTest {
            val shop = BuyTheme(shops, session)("OCEAN")

            assertEquals(true, shop.theme("OCEAN")?.owned)
            assertEquals(listOf("ensure", "buy OCEAN"), calls)
        }

    @Test
    fun `a theme the shop does not sell is none`() {
        assertNull(SHOP.theme("NONE_SUCH"))
    }

    private class RecordingShop(
        private val calls: MutableList<String>,
    ) : ShopRepository {
        override suspend fun shop(): Shop {
            calls += "shop"
            return SHOP
        }

        override suspend fun buy(themeId: String): Shop {
            calls += "buy $themeId"
            return SHOP.copy(
                themes =
                    SHOP.themes.map {
                        if (it.id ==
                            themeId
                        ) {
                            it.copy(owned = true)
                        } else {
                            it
                        }
                    },
                points = 0,
            )
        }
    }

    private class RecordingSessions(
        private val calls: MutableList<String>,
    ) : SessionRepository {
        override suspend fun ensure(): String {
            calls += "ensure"
            return "p1"
        }
    }

    private companion object {
        val SHOP =
            Shop(
                themes = listOf(ShopTheme("NEON_NIGHT", 220, owned = false), ShopTheme("OCEAN", 220, owned = false)),
                points = 220,
                registered = true,
            )
    }
}

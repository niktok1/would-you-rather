package io.ntole.wyr.server.shop

import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.shop.ShopDto
import io.ntole.wyr.core.shop.ShopThemeDto
import io.ntole.wyr.server.auth.AccountStore
import io.ntole.wyr.server.db.Purchases
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.AccountDeletion
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.player.StatsStore
import io.ntole.wyr.server.plugins.ApiFailure
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The shop (CLAUDE.md §8d, *The shop*) through its store at READ COMMITTED, as ReactionStoreTest drives
 * reactions, so a player's points and purchases can be read straight after, and purchases racing staged.
 */
class ShopStoreTest {
    private val url = h2Url("wyr-shop-store-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) { SchemaUtils.create(*appTables) }
    }

    @Test
    fun `the shop lists every theme in the catalog's order, at the price, with the player's points`() {
        val player = newPlayer(points = 5)

        assertEquals(
            ShopDto(
                themes = ShopCatalog.THEMES.map { id -> ShopThemeDto(id, price = PRICE, owned = false) },
                totalPoints = 5,
                registered = false,
            ),
            transaction(database) { ShopStore.shopOf(player, PRICE) },
        )
        assertEquals(listOf("NEON_NIGHT", "OCEAN", "FOREST", "SUNSET"), ShopCatalog.THEMES)
    }

    @Test
    fun `the shop of a player who does not exist is none`() {
        assertNull(transaction(database) { ShopStore.shopOf("no-such-player", PRICE) })
    }

    @Test
    fun `a purchase takes the price, keeps what it paid, and shows the theme owned`() {
        val buyer = newPlayer(points = PRICE + 3, registered = true)

        transaction(database) { ShopStore.buy(buyer, "OCEAN", PRICE, now = 1_000) }

        val shop = transaction(database) { ShopStore.shopOf(buyer, price = 99) }
        assertEquals(3, shop?.totalPoints)
        assertEquals(true, shop?.registered)
        assertEquals(listOf("OCEAN"), shop?.themes?.filter { it.owned }?.map { it.id })
        assertEquals(setOf(99), shop?.themes?.map { it.price }?.toSet(), "every theme at the price asked for now")
        assertEquals(listOf(PRICE), paidBy(buyer), "what it paid, whatever the price is by now")
    }

    @Test
    fun `an unknown item, one owned already and too few points each take nothing`() {
        val buyer = newPlayer(points = PRICE)
        transaction(database) { ShopStore.buy(buyer, "FOREST", price = 0) }

        assertEquals(ErrorCode.ITEM_NOT_FOUND, refusalOf { ShopStore.buy(buyer, "RAINBOW", PRICE) })
        assertEquals(ErrorCode.ALREADY_OWNED, refusalOf { ShopStore.buy(buyer, "FOREST", PRICE) })
        assertEquals(ErrorCode.NOT_ENOUGH_POINTS, refusalOf { ShopStore.buy(buyer, "SUNSET", PRICE + 1) })

        assertEquals(PRICE, pointsOf(buyer))
        assertEquals(listOf(0), paidBy(buyer))
    }

    /**
     * A double tap, or a resend, many times over: each purchase after the first waits on the player's
     * row lock and then finds the theme owned, so the price is taken once even when it is every point
     * the player had.
     */
    @Test
    fun `a burst of purchases of one theme buys it once and takes its price once`() {
        val buyer = newPlayer(points = PRICE)
        val buy = { refusalOrNull { ShopStore.buy(buyer, "NEON_NIGHT", PRICE) } }

        val outcomes = raceBehindFirst(url, database, *Array(BURST) { buy })

        assertEquals(listOf<ErrorCode?>(null) + List(BURST - 1) { ErrorCode.ALREADY_OWNED }, outcomes)
        assertEquals(0, pointsOf(buyer))
        assertEquals(listOf(PRICE), paidBy(buyer))
    }

    /** Two themes the player can afford only one of: the second waits, then finds too few points. */
    @Test
    fun `two purchases of different themes racing for the last points buy exactly one`() {
        val buyer = newPlayer(points = PRICE)

        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { refusalOrNull { ShopStore.buy(buyer, "OCEAN", PRICE) } },
                { refusalOrNull { ShopStore.buy(buyer, "SUNSET", PRICE) } },
            )

        assertNull(first)
        assertEquals(ErrorCode.NOT_ENOUGH_POINTS, second)
        assertEquals(0, pointsOf(buyer))
        assertEquals(listOf(PRICE), paidBy(buyer))
    }

    @Test
    fun `the stats count what the shop took as spent, so the total still adds up`() {
        val buyer = newPlayer(points = PRICE + 4, registered = true)

        transaction(database) { ShopStore.buy(buyer, "OCEAN", PRICE) }

        val stats = transaction(database) { StatsStore.of(buyer) }
        assertEquals(4, stats?.totalPoints)
        assertEquals(PRICE, stats?.pointsSpent)
    }

    @Test
    fun `deleting a player deletes their purchases`() {
        val buyer = newPlayer(points = PRICE * 2, registered = true)
        transaction(database) {
            ShopStore.buy(buyer, "OCEAN", PRICE)
            ShopStore.buy(buyer, "FOREST", PRICE)
        }

        transaction(database) { AccountDeletion.delete(buyer) }

        assertEquals(emptyList(), paidBy(buyer))
    }

    private fun newPlayer(
        points: Int,
        registered: Boolean = false,
    ): String =
        transaction(database) {
            val id = PlayerStore.createGuest().id
            PlayerStore.addPoints(id, points)
            if (registered) AccountStore.register(id, "buyer-${id.take(8)}", "not-a-password-hash")
            id
        }

    /**
     * What [block] was refused with, or null when it went through. Catches only the refusal, never the
     * database's failure on the key, so Exposed still reruns a transaction that met one.
     */
    private fun refusalOrNull(block: () -> Unit): ErrorCode? =
        try {
            block()
            null
        } catch (refused: ApiFailure) {
            refused.code
        }

    private fun refusalOf(block: () -> Unit): ErrorCode =
        assertFailsWith<ApiFailure> { transaction(database) { block() } }.code

    private fun pointsOf(playerId: String): Int? = transaction(database) { PlayerStore.find(playerId)?.totalPoints }

    private fun paidBy(playerId: String): List<Int> =
        transaction(database) {
            Purchases.select(Purchases.price).where { Purchases.playerId eq playerId }.map { it[Purchases.price] }
        }

    private companion object {
        const val PRICE = 220
        const val BURST = 8
    }
}

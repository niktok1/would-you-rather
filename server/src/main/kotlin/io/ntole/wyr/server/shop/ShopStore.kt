package io.ntole.wyr.server.shop

import io.ntole.wyr.core.shop.ShopDto
import io.ntole.wyr.core.shop.ShopThemeDto
import io.ntole.wyr.server.db.Identities
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.Purchases
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select

/** The shop (CLAUDE.md §8d, *The shop*): what a player owns, and buying more with their points. */
object ShopStore {
    /**
     * The shop as [playerId] sees it, or null for a player that does not exist: every theme of the
     * [ShopCatalog], in its order, at [price], each marked owned when the player bought it, and the
     * player's points and whether they are registered, a username or a Play Games link, since only a
     * registered player may buy. Must run inside a transaction.
     *
     * One statement, the player's row left-joined to their purchases, one row per purchase or one for
     * none, with the link counted beside it: two statements could straddle a purchase committing in
     * between, and show a theme owned with its price not yet taken, or the price taken with nothing to
     * show for it (CLAUDE.md §4).
     *
     * A purchase keeps what it paid; [price] is what the catalog sells at now, which is all an owned
     * theme's price is shown as.
     */
    fun shopOf(
        playerId: String,
        price: Int,
    ): ShopDto? {
        val links =
            wrapAsExpression<Long>(
                Identities.select(Identities.playerId.count()).where { Identities.playerId eq playerId },
            )
        val rows =
            Players
                .join(Purchases, JoinType.LEFT, Players.id, Purchases.playerId)
                .select(Players.totalPoints, Players.username, links, Purchases.itemId)
                .where { Players.id eq playerId }
                .toList()
        val player = rows.firstOrNull() ?: return null
        val owned = rows.mapNotNull { row -> row.getOrNull(Purchases.itemId) }.toSet()
        val linked = checkNotNull(player[links]) { "a COUNT subquery came back null" } > 0

        return ShopDto(
            themes = ShopCatalog.THEMES.map { id -> ShopThemeDto(id = id, price = price, owned = id in owned) },
            totalPoints = player[Players.totalPoints],
            registered = player[Players.username] != null || linked,
        )
    }

    /**
     * Buys [itemId] for [playerId] at [price], taken from their points. Must run inside a transaction,
     * after the caller has found the player and seen they are registered.
     *
     * An id the catalog lacks is 404 [ApiFailure.itemNotFound], an item the player owns 409
     * [ApiFailure.alreadyOwned], and fewer points than [price] 409 [ApiFailure.notEnoughPoints]; each
     * takes nothing.
     *
     * The player's row is locked before what they own is read (CLAUDE.md §4), as a submission locks
     * its author's: every purchase of theirs takes that lock first, so a second one racing waits, then
     * reads what the first committed. So of two purchases of one item, a double tap or a resend, the
     * second finds it owned and takes nothing, even when the first spent the player's last points; and
     * of two of different items that together cost more than the player has, the second finds too few
     * points. The price is still taken as a compare-and-set on having it ([PlayerStore.spend]), and
     * the primary key on the player and the item still holds a purchase to one row whatever reaches
     * the table.
     */
    fun buy(
        playerId: String,
        itemId: String,
        price: Int,
        now: Long = System.currentTimeMillis(),
    ) {
        if (itemId !in ShopCatalog.THEMES) throw ApiFailure.itemNotFound(itemId)
        Players
            .select(Players.id)
            .where { Players.id eq playerId }
            .forUpdate()
            .singleOrNull()
            ?: throw ApiFailure.unauthorized("unknown player")
        if (owns(playerId, itemId)) throw ApiFailure.alreadyOwned(itemId)
        if (!PlayerStore.spend(playerId, price)) throw ApiFailure.notEnoughPoints(price, "buying $itemId")

        Purchases.insert { row ->
            row[Purchases.playerId] = playerId
            row[Purchases.itemId] = itemId
            row[Purchases.price] = price
            row[purchasedAt] = now
        }
    }

    private fun owns(
        playerId: String,
        itemId: String,
    ): Boolean =
        Purchases
            .select(Purchases.itemId)
            .where { (Purchases.playerId eq playerId) and (Purchases.itemId eq itemId) }
            .limit(1)
            .any()
}

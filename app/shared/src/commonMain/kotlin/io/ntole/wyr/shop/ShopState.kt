package io.ntole.wyr.shop

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.shop.Shop
import io.ntole.wyr.core.domain.shop.ShopTheme
import kotlin.time.Duration

/**
 * What the shop shows (CLAUDE.md §8d, *The shop*): what it sells and the player's points, as last read,
 * the theme a purchase is asked about, and how the last read or purchase failed.
 */
data class ShopState(
    /** The shop as last read, or null until a read works. */
    val shop: Shop? = null,
    /** Why the last read failed, until the next action starts. */
    val readFailure: ShopFailure? = null,
    /** Why the last purchase took nothing, until the next action starts. */
    val buyFailure: ShopFailure? = null,
    /** The theme the dialog before a purchase is open for, by its id, or null while none is. */
    val confirming: String? = null,
    /** The theme the last purchase bought, until the screen puts it on ([ShopActions.boughtWorn]). */
    val bought: String? = null,
    /** The action in flight, or null when idle. Only one runs at a time. */
    val running: ShopAction? = null,
) {
    val isBusy: Boolean get() = running != null

    /** Whether [theme] can be bought now: the player registered, with the points, and nothing in flight. */
    fun canBuy(theme: ShopTheme): Boolean {
        val read = shop ?: return false
        return !isBusy && !theme.owned && read.registered && read.points >= theme.price
    }

    /**
     * Whether the player, registered, has fewer points than a theme they do not own costs, which the
     * shop says once: not while none is read, and not for a guest, who is told to register instead.
     */
    val tooFewPoints: Boolean
        get() {
            val read = shop ?: return false
            return read.registered && read.themes.any { !it.owned && read.points < it.price }
        }

    /** Whether the player last read is a guest, who may not buy, which the shop says: not while none is read. */
    val isGuest: Boolean get() = shop?.registered == false
}

/** What the shop can be busy doing. */
enum class ShopAction {
    LOAD,
    BUY,
}

/** How an action failed. [retryAfter] is the wait the server named with a [DomainError.RATE_LIMITED], or null. */
data class ShopFailure(
    val error: DomainError,
    val retryAfter: Duration? = null,
)

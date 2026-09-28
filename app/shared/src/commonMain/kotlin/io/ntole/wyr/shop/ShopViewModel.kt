package io.ntole.wyr.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.shop.BuyTheme
import io.ntole.wyr.core.domain.shop.GetShop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the shop can ask for, so the screen takes one argument for all of it. */
interface ShopActions {
    /** Reads the shop again. */
    fun refresh()

    /** Opens the dialog before buying the theme of [themeId], which shows it larger. */
    fun askToBuy(themeId: String)

    /** Closes that dialog, buying nothing. */
    fun cancelBuy()

    /** Buys the theme the dialog is open for, and closes it. */
    fun buy()

    /** The screen put on [ShopState.bought]: takes it down. */
    fun boughtWorn()
}

/**
 * Drives the shop (CLAUDE.md §8d, *The shop*): read through [GetShop] each time it is shown, a theme
 * bought through [BuyTheme] once the player confirms it in a dialog. One action at a time. A purchase's
 * answer is the shop after it, so nothing is read again after one that worked; one that failed reads the
 * shop again, since it may have been taken already (its answer lost, then `ALREADY_OWNED`) or the points
 * moved meanwhile. Putting a theme on is the screen's, through [ThemeViewModel].
 *
 * [analytics] hear of it (CLAUDE.md §8g): the shop opened, a theme bought, and every failure shown.
 */
class ShopViewModel(
    private val getShop: GetShop,
    private val buyTheme: BuyTheme,
    private val analytics: Analytics,
) : ViewModel(),
    ShopActions {
    private val _state = MutableStateFlow(ShopState())
    val state: StateFlow<ShopState> = _state.asStateFlow()

    /**
     * The shop is shown: read again, and the analytics hear of it when that begins a [newVisit], not when
     * a rotation's composition shows the same one (CLAUDE.md §8g).
     */
    fun shown(newVisit: Boolean = true) {
        if (newVisit) analytics.track(AnalyticsEvent.SHOP_OPENED)
        refresh()
    }

    override fun refresh() = perform(ShopAction.LOAD) { read() }

    override fun askToBuy(themeId: String) {
        val theme = _state.value.shop?.theme(themeId) ?: return
        if (_state.value.canBuy(theme)) _state.update { it.copy(confirming = themeId, buyFailure = null) }
    }

    override fun cancelBuy() = _state.update { it.copy(confirming = null) }

    override fun buy() {
        val themeId = _state.value.confirming ?: return
        val theme = _state.value.shop?.theme(themeId)
        _state.update { it.copy(confirming = null) }
        if (theme == null || !_state.value.canBuy(theme)) return
        perform(ShopAction.BUY) {
            try {
                val after = buyTheme(themeId)
                analytics.track(
                    AnalyticsEvent.THEME_BOUGHT,
                    mapOf(AnalyticsProperty.THEME to themeId, AnalyticsProperty.PRICE to theme.price),
                )
                _state.update { it.copy(shop = after, bought = themeId) }
            } catch (failure: WyrException) {
                reportShown(failure.error, ACTION_BUY)
                _state.update { it.copy(buyFailure = failure.toShopFailure()) }
                read()
            }
        }
    }

    override fun boughtWorn() = _state.update { it.copy(bought = null) }

    /** Reads the shop; a read that fails keeps what was shown and says why. */
    private suspend fun read() {
        try {
            val shop = getShop()
            _state.update { it.copy(shop = shop) }
        } catch (failure: WyrException) {
            reportShown(failure.error, ACTION_SHOP)
            _state.update { it.copy(readFailure = failure.toShopFailure()) }
        }
    }

    /** Runs [block] as the one action in flight; a second while one runs is ignored. */
    private fun perform(
        action: ShopAction,
        block: suspend () -> Unit,
    ) {
        if (_state.value.isBusy) return
        _state.update { it.copy(running = action, readFailure = null, buyFailure = null) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(running = null) }
            }
        }
    }

    private fun reportShown(
        error: DomainError,
        action: String,
    ) {
        analytics.track(
            AnalyticsEvent.ERROR_SHOWN,
            mapOf(AnalyticsProperty.CODE to error.name, AnalyticsProperty.ACTION to action),
        )
    }
}

private fun WyrException.toShopFailure(): ShopFailure = ShopFailure(error, retryAfter)

private const val ACTION_SHOP = "shop"
private const val ACTION_BUY = "buy_theme"

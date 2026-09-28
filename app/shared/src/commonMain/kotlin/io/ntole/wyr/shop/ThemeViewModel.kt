package io.ntole.wyr.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.session.CurrentSession
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.theme.GameTheme
import io.ntole.wyr.theme.GameThemes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The theme the game wears (CLAUDE.md §8d, *The shop*): the one the player on this device put on, kept
 * on the device, and [GameThemes.Default] for anyone else.
 *
 * Kept in [storage], the session's, under a key of each environment's own, as the session is (§8e):
 * `wyr.theme.local`, `.dev` or `.prod`, holding the player's id on the first line and the theme's on the
 * second. It is worn only while the session stored names that player ([CurrentSession]), so a logout,
 * a login to another account or a dead session replaced by a fresh guest takes it off with no network,
 * and the player logging back in puts it on again. Owning a theme is the server's; the shop hands in
 * what the player owns as it reads it ([owned]), and a theme worn that they no longer own comes off.
 */
class ThemeViewModel(
    private val storage: TokenStorage,
    environment: WyrEnvironment,
    private val session: CurrentSession,
    private val analytics: Analytics,
) : ViewModel() {
    private val key = keyFor(environment)
    private var kept: Kept? = Kept.parse(storage.read(key))
    private val worn = MutableStateFlow(themeFor(session.current()))

    val theme: StateFlow<GameTheme> = worn.asStateFlow()

    init {
        viewModelScope.launch { session.sessions.collect { player -> worn.value = themeFor(player) } }
    }

    /**
     * Puts on the theme of [themeId] for the player on this device, then keeps it for the next launch.
     * Nothing happens with no session stored, or for a theme this build has no colours for. A write that
     * fails leaves it worn for this run only, as the language's does.
     */
    fun wear(themeId: String) {
        val player = session.current() ?: return
        val theme = GameThemes.ofId(themeId) ?: return
        if (theme == worn.value && kept?.playerId == player) return
        keep(Kept(player, theme.id))
        worn.value = theme
        analytics.track(AnalyticsEvent.THEME_APPLIED, mapOf(AnalyticsProperty.THEME to theme.id))
    }

    /**
     * The themes the player on this device owns, [ids], as the shop just read them: a theme worn that is
     * not among them, a server's data reset say, comes off.
     */
    fun owned(ids: Set<String>) {
        val player = session.current() ?: return
        val current = kept?.takeIf { it.playerId == player } ?: return
        if (current.themeId == GameThemes.DEFAULT_ID || current.themeId in ids) return
        keep(Kept(player, GameThemes.DEFAULT_ID))
        worn.value = GameThemes.Default
    }

    private fun themeFor(player: String?): GameTheme =
        kept?.takeIf { player != null && it.playerId == player }?.let { GameThemes.ofId(it.themeId) }
            ?: GameThemes.Default

    private fun keep(choice: Kept) {
        kept = choice
        viewModelScope.launch {
            try {
                storage.write(key, "${choice.playerId}\n${choice.themeId}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                // Worn for this run only, as the KDoc says.
            }
        }
    }

    /** The theme [playerId] put on, by its id. Neither holds a line break. */
    private data class Kept(
        val playerId: String,
        val themeId: String,
    ) {
        companion object {
            fun parse(stored: String?): Kept? {
                val lines = stored?.lines() ?: return null
                val player = lines.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
                val theme = lines.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
                return Kept(player, theme)
            }
        }
    }

    internal companion object {
        fun keyFor(environment: WyrEnvironment): String = "wyr.theme.${environment.name.lowercase()}"
    }
}

package io.ntole.wyr.language

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.network.TokenStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The language the game is shown in (CLAUDE.md §8f), as the device keeps it: [Language.DEFAULT],
 * Serbian Cyrillic, until the player picks another on the Account screen.
 *
 * Kept in [storage], the platform's own key-value storage the session is kept in, under [KEY], one
 * key for the device whatever server the build talks to: the language is the player's, not the
 * environment's (§8e), so every build on a desktop, an iPhone or a browser shows the one last
 * picked on it. A language picked is reported to [analytics], by its tag (§8g).
 */
class LanguageViewModel(
    private val storage: TokenStorage,
    private val analytics: Analytics,
) : ViewModel() {
    private val selected = MutableStateFlow(Language.ofTag(storage.read(KEY)))

    val language: StateFlow<Language> = selected.asStateFlow()

    /**
     * Shows the game in [language] at once, then keeps it for the next launch. A write that fails
     * leaves it the language of this run only: the next launch opens in the one kept before, which
     * is no reason to stop the game or to say anything.
     */
    fun select(language: Language) {
        if (language == selected.value) return
        selected.value = language
        analytics.track(AnalyticsEvent.LANGUAGE_CHANGED, mapOf(AnalyticsProperty.LANGUAGE to language.tag))
        viewModelScope.launch {
            try {
                storage.write(KEY, language.tag)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                // Kept for this run only, as the KDoc says.
            }
        }
    }

    companion object {
        /** Beside the sessions' keys (`SessionStore.keyFor`), and none of them. */
        const val KEY: String = "wyr.language"
    }
}

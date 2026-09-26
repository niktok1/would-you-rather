package io.ntole.wyr.core.domain.analytics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Product analytics (CLAUDE.md §8g): what players do in the game, sent to a service so the game can be
 * made better. A port, so the game's screens and use cases report what happened without knowing where
 * it goes; `:core:network` implements it over PostHog's HTTP API.
 *
 * Nothing here may block or fail the game: every call returns at once and none throws, a send that
 * fails being the implementation's to retry or drop; only [flushAndWait], for an app about to end,
 * waits, as long as its caller lets it. Events are named by [AnalyticsEvent] and their properties by
 * [AnalyticsProperty].
 *
 * A property holds a string, a number, a boolean, a list of those, or null. Never anything that names
 * the player or says where they are: no username, no email, no password, no place, and no question's
 * text, only its id; a category is its id too.
 */
public interface Analytics {
    /**
     * Whether the player lets the game send analytics: on until they turn it off, on the Account
     * screen's Statistics switch, and then off on this device until they turn it on again.
     */
    public val enabled: StateFlow<Boolean>

    /**
     * Turns analytics on or off, and keeps that for the next launch. Off sends nothing more and drops
     * what was waiting to be sent.
     */
    public fun setEnabled(enabled: Boolean)

    /** Reports [event], with [properties] of its own beside those every event carries. */
    public fun track(
        event: String,
        properties: Map<String, Any?> = emptyMap(),
    )

    /** Reports that the screen [name] is shown, and names it on every event after, until the next. */
    public fun screen(name: String)

    /**
     * Says that the player on this device is [playerId], an account's player, from a registration or a
     * login: every event after is theirs, and the service joins what this install sent before to them.
     */
    public fun identify(playerId: String)

    /**
     * Forgets who the player was, for a logout (or, once there is one, an account's deletion): every
     * event after is a fresh anonymous player's, joined to nobody before.
     */
    public fun reset()

    /** Sends what is waiting now, rather than on the next batch or timer: as the app goes to the background. */
    public fun flush()

    /**
     * Sends what is waiting now, after any send under way, and returns once it is sent or could not be:
     * for an app about to end, whose process would not outlive a [flush] (a desktop window closed). The
     * caller bounds how long it waits; nothing throws but the caller's own cancellation.
     */
    public suspend fun flushAndWait()

    public companion object {
        /** Sends nothing and keeps nothing: for a client with no analytics, and for tests. */
        public val None: Analytics = NoAnalytics
    }
}

private object NoAnalytics : Analytics {
    override val enabled: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    override fun setEnabled(enabled: Boolean) = Unit

    override fun track(
        event: String,
        properties: Map<String, Any?>,
    ) = Unit

    override fun screen(name: String) = Unit

    override fun identify(playerId: String) = Unit

    override fun reset() = Unit

    override fun flush() = Unit

    override suspend fun flushAndWait() = Unit
}

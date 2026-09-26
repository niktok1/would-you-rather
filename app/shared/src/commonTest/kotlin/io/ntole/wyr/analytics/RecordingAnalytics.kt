package io.ntole.wyr.analytics

import io.ntole.wyr.core.domain.analytics.Analytics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [Analytics] that keeps what it is told, in order, for a test to read: each event with its own
 * properties, a screen as `$screen` naming it, and `$identify`, `reset` and `flush` as they come.
 */
class RecordingAnalytics(
    enabled: Boolean = true,
) : Analytics {
    /** One thing the analytics were told. */
    data class Recorded(
        val name: String,
        val properties: Map<String, Any?> = emptyMap(),
    )

    val recorded = mutableListOf<Recorded>()

    private val switch = MutableStateFlow(enabled)
    override val enabled: StateFlow<Boolean> = switch

    /** The events tracked, by name, screens and the rest left out. */
    val events: List<Recorded> get() = recorded.filter { it.name !in NOT_EVENTS }

    fun named(event: String): List<Recorded> = recorded.filter { it.name == event }

    override fun setEnabled(enabled: Boolean) {
        switch.value = enabled
    }

    override fun track(
        event: String,
        properties: Map<String, Any?>,
    ) {
        recorded += Recorded(event, properties.toMap())
    }

    override fun screen(name: String) {
        recorded += Recorded(SCREEN, mapOf(SCREEN_NAME to name))
    }

    override fun identify(playerId: String) {
        recorded += Recorded(IDENTIFY, mapOf(PLAYER to playerId))
    }

    override fun reset() {
        recorded += Recorded(RESET)
    }

    override fun flush() {
        recorded += Recorded(FLUSH)
    }

    companion object {
        const val SCREEN = "\$screen"
        const val SCREEN_NAME = "\$screen_name"
        const val IDENTIFY = "\$identify"
        const val PLAYER = "player"
        const val RESET = "reset"
        const val FLUSH = "flush"
        private val NOT_EVENTS = setOf(SCREEN, IDENTIFY, RESET, FLUSH)
    }
}

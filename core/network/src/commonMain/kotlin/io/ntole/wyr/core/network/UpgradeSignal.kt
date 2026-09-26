package io.ntole.wyr.core.network

import io.ntole.wyr.core.domain.update.AppUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The game's [AppUpdate]: raised by the HTTP client the first time any answer is `UPGRADE_REQUIRED`
 * ([WyrHttpClient.create]), and never lowered, since the server serves this build nothing more.
 */
public class UpgradeSignal : AppUpdate {
    private val raised = MutableStateFlow(false)

    override val required: StateFlow<Boolean> = raised.asStateFlow()

    internal fun raise() {
        raised.value = true
    }
}

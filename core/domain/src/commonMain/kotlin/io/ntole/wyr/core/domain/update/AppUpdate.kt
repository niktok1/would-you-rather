package io.ntole.wyr.core.domain.update

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the server has refused this build as too old (CLAUDE.md §8b, *Minimum client version*).
 *
 * [required] turns true the first time any call is answered `UPGRADE_REQUIRED`, and stays true for the
 * app's life: the server serves this build nothing more, whatever is asked. The game then shows only
 * the screen that says a new version is available. Implemented where the answer arrives, the HTTP
 * client in `:core:network`, so no repository has to pass it on.
 */
public interface AppUpdate {
    public val required: StateFlow<Boolean>
}

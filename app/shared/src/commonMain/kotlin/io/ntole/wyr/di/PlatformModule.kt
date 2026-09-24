package io.ntole.wyr.di

import io.ntole.wyr.core.network.environment.WyrEnvironment
import org.koin.core.module.Module

/**
 * Bindings only a platform can provide: its [io.ntole.wyr.core.network.TokenStorage].
 *
 * This and [platformApiBaseUrl] are the one place per-platform wiring is allowed to live —
 * everything above them is common code (CLAUDE.md §3 entry-point rule).
 */
expect fun platformModule(): Module

/**
 * Where this platform sends its requests for [environment]: the environment's own URL, unless the
 * platform lets its launch name another, as desktop's `WYR_API_BASE_URL` does.
 */
internal expect fun platformApiBaseUrl(environment: WyrEnvironment): String

package io.ntole.wyr.di

import org.koin.core.module.Module

/**
 * Bindings only a platform can provide: its [io.ntole.wyr.core.network.TokenStorage].
 *
 * One of the three places per-platform code lives in this module, the others being Android's back
 * ([io.ntole.wyr.navigation.SystemBack]) and Android's rotation, which the analytics let pass
 * (`rememberConfigurationChanging`) — everything else is common code (CLAUDE.md §3 entry-point rule).
 */
expect fun platformModule(): Module

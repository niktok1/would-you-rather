package io.ntole.wyr.di

import org.koin.core.module.Module

/**
 * Bindings only a platform can provide: its [io.ntole.wyr.core.network.TokenStorage].
 *
 * One of the four places per-platform code lives in this module, the others being Android's back
 * ([io.ntole.wyr.navigation.SystemBack]), Android's rotation, which the analytics let pass
 * (`rememberConfigurationChanging`), and Android's Google services behind the domain's ports
 * (`androidDeviceServices`, handed to [initKoin] as [DeviceServices]) — everything else is common code
 * (CLAUDE.md §3 entry-point rule).
 */
expect fun platformModule(): Module

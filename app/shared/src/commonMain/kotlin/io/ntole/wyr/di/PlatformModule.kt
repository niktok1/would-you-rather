package io.ntole.wyr.di

import org.koin.core.module.Module

/**
 * Bindings only a platform can provide: its [io.ntole.wyr.core.network.TokenStorage].
 *
 * One of the two places per-platform code lives in this module, the other being Android's back
 * ([io.ntole.wyr.navigation.SystemBack]) — everything else is common code (CLAUDE.md §3 entry-point
 * rule).
 */
expect fun platformModule(): Module

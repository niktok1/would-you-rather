package io.ntole.wyr.di

import org.koin.core.module.Module

/**
 * Bindings only a platform can provide: its [io.ntole.wyr.core.network.TokenStorage] and the API
 * base URL to reach from that platform.
 *
 * This is the one place per-platform wiring is allowed to live — everything above it is common
 * code (CLAUDE.md §3 entry-point rule).
 */
expect fun platformModule(): Module

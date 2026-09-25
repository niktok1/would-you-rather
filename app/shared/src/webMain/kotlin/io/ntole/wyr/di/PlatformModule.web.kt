package io.ntole.wyr.di

import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.WebTokenStorage
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<TokenStorage> { WebTokenStorage() }
        // No RecoverySecretStorage: a browser guest stays bound to this storage (CLAUDE.md §8a, *Recovery*).
    }

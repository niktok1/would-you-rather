package io.ntole.wyr.di

import io.ntole.wyr.core.network.JvmTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<TokenStorage> { JvmTokenStorage() }
        // No RecoverySecretStorage: a desktop guest stays bound to this storage (CLAUDE.md §8a, *Recovery*).
    }

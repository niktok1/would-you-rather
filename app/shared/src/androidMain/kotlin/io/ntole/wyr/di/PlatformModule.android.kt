package io.ntole.wyr.di

import io.ntole.wyr.core.network.AndroidRecoverySecretStorage
import io.ntole.wyr.core.network.AndroidTokenStorage
import io.ntole.wyr.core.network.RecoverySecretStorage
import io.ntole.wyr.core.network.TokenStorage
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<TokenStorage> { AndroidTokenStorage(androidContext()) }
        // Block Store, which a reinstall and a new phone keep (CLAUDE.md §8a, *Recovery*).
        single<RecoverySecretStorage> { AndroidRecoverySecretStorage(androidContext()) }
    }

package io.ntole.wyr.di

import io.ntole.wyr.core.network.IosRecoverySecretStorage
import io.ntole.wyr.core.network.IosTokenStorage
import io.ntole.wyr.core.network.RecoverySecretStorage
import io.ntole.wyr.core.network.TokenStorage
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<TokenStorage> { IosTokenStorage() }
        // The iCloud Keychain, which a reinstall keeps and this person's other iPhones share (CLAUDE.md §8a).
        single<RecoverySecretStorage> { IosRecoverySecretStorage() }
    }

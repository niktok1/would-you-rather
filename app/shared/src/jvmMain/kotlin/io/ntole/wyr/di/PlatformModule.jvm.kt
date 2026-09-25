package io.ntole.wyr.di

import io.ntole.wyr.core.network.JvmTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<TokenStorage> { JvmTokenStorage() }
    }

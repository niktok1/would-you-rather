package io.ntole.wyr.di

import io.ntole.wyr.core.network.JvmTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<TokenStorage> { JvmTokenStorage() }
        single(named(API_BASE_URL)) { DevApiBaseUrl.LOCALHOST }
    }

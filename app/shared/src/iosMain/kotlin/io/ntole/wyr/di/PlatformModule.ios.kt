package io.ntole.wyr.di

import io.ntole.wyr.core.network.IosTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<TokenStorage> { IosTokenStorage() }
        // The iOS simulator shares the host's loopback, so localhost is correct here.
        single(named(API_BASE_URL)) { DevApiBaseUrl.LOCALHOST }
    }

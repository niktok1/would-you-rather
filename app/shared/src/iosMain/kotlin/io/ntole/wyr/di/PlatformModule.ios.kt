package io.ntole.wyr.di

import io.ntole.wyr.core.network.IosTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<TokenStorage> { IosTokenStorage() }
    }

internal actual fun platformApiBaseUrl(environment: WyrEnvironment): String = environment.apiBaseUrl

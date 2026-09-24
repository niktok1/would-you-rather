package io.ntole.wyr.di

import io.ntole.wyr.core.network.AndroidTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<TokenStorage> { AndroidTokenStorage(androidContext()) }
    }

internal actual fun platformApiBaseUrl(environment: WyrEnvironment): String = environment.apiBaseUrl

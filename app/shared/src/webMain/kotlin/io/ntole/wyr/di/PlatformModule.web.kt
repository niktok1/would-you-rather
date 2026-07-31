package io.ntole.wyr.di

import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.WebTokenStorage
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<TokenStorage> { WebTokenStorage() }
        // Browser clients are cross-origin against the API, so the server's ALLOWED_WEB_ORIGINS
        // must name wherever this page is served from or every request fails CORS preflight.
        single(named(API_BASE_URL)) { DevApiBaseUrl.LOCALHOST }
    }

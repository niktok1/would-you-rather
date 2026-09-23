package io.ntole.wyr.di

import io.ntole.wyr.core.data.di.dataModule
import io.ntole.wyr.dev.DevConsoleViewModel
import io.ntole.wyr.play.PlayViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

// Internal, not private, so a test can resolve every ViewModel from the real graph.
internal val uiModule =
    module {
        viewModelOf(::PlayViewModel)

        // Not viewModelOf: the base URL is a plain String, found only by its qualifier.
        viewModel {
            DevConsoleViewModel(
                apiBaseUrl = get(named(API_BASE_URL)),
                sessions = get(),
                diagnostics = get(),
                questions = get(),
                queue = get(),
                getNextQuestion = get(),
                castVote = get(),
                httpTrace = get(),
            )
        }
    }

/**
 * Starts Koin.
 *
 * Called once per process from each platform's entry point. [appDeclaration] is how Android hands
 * in its `Context`, which the shared code otherwise has no way to obtain — which is also why Koin
 * is an `api` dependency of this module rather than an implementation detail.
 *
 * Returns nothing: no entry point needs the `KoinApplication`, and keeping it out of the signature
 * keeps one more Koin type off their classpaths.
 */
fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    startKoin {
        appDeclaration()

        val platform = platformModule()
        modules(platform)

        // The base URL comes from the platform module, so it has to be resolved after that module
        // is registered rather than passed in from here.
        val baseUrl = koin.get<String>(named(API_BASE_URL))
        modules(dataModule(baseUrl), uiModule)
    }
}

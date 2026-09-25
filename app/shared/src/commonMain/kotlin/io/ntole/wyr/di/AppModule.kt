package io.ntole.wyr.di

import io.ntole.wyr.account.AccountViewModel
import io.ntole.wyr.core.data.di.dataModule
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.dev.DevConsoleViewModel
import io.ntole.wyr.dev.submission.SubmissionConsoleViewModel
import io.ntole.wyr.play.PlayViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

// Internal, not private, so a test can resolve every ViewModel from the real graph.
internal val uiModule =
    module {
        viewModelOf(::PlayViewModel)
        viewModelOf(::AccountViewModel)

        // Not viewModelOf: the time source is a default, not a binding.
        viewModel {
            DevConsoleViewModel(
                environment = get(),
                sessions = get(),
                diagnostics = get(),
                questions = get(),
                queue = get(),
                getNextQuestion = get(),
                castVote = get(),
                getPlayerStats = get(),
                httpTrace = get(),
            )
        }

        // Not viewModelOf: the time source is a default, not a binding.
        viewModel {
            SubmissionConsoleViewModel(submitQuestion = get(), getMySubmissions = get(), sessions = get())
        }
    }

/**
 * Starts Koin for the server environment [environmentName] names (CLAUDE.md §8e), by
 * [WyrEnvironment.parse]: no name is [WyrEnvironment.LOCAL], and one it does not know stops the app
 * here, before anything has started.
 *
 * Called once per process from each platform's entry point, which names the environment it was
 * built or started for: Android's product flavor, the web build's `-Pwyr.env`, desktop's `WYR_ENV`
 * variable, the iOS app's Info.plist. The name has no default, so no entry point can leave it out
 * by accident and end up on LOCAL; `null` is for a build that set none. It is a plain string so
 * that `:core:network` stays off the entry points' classpaths.
 *
 * [appDeclaration] is how Android hands in its `Context`, which the shared code otherwise has no
 * way to obtain — which is also why Koin is an `api` dependency of this module rather than an
 * implementation detail.
 *
 * Returns nothing: no entry point needs the `KoinApplication`, and keeping it out of the signature
 * keeps one more Koin type off their classpaths.
 */
fun initKoin(
    environmentName: String?,
    appDeclaration: KoinAppDeclaration = {},
) {
    val environment = WyrEnvironment.parse(environmentName)
    startKoin {
        appDeclaration()
        modules(platformModule())
        modules(appModules(environment))
    }
}

/**
 * Every module but the platform's, for [environment]: the data module sends every request to its
 * URL, and the environment is bound for the screens that show it. Nothing can put another URL in its
 * place, so what the console shows is where requests go. Internal, not private, so a test can load
 * them as [initKoin] does.
 */
internal fun appModules(environment: WyrEnvironment): List<Module> =
    listOf(
        module { single { environment } },
        dataModule(environment),
        uiModule,
    )

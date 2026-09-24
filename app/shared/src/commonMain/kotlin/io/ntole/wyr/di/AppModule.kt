package io.ntole.wyr.di

import io.ntole.wyr.core.data.di.dataModule
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.dev.DevConsoleViewModel
import io.ntole.wyr.dev.moderation.ModerationConsoleViewModel
import io.ntole.wyr.dev.submission.SubmissionConsoleViewModel
import io.ntole.wyr.play.PlayViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

// Internal, not private, so a test can resolve every ViewModel from the real graph.
internal val uiModule =
    module {
        viewModelOf(::PlayViewModel)

        // Not viewModelOf: the time source is a default, not a binding.
        viewModel {
            ModerationConsoleViewModel(
                getPendingSubmissions = get(),
                approveSubmission = get(),
                rejectSubmission = get(),
            )
        }

        // Not viewModelOf: the base URL is a plain String, found only by its qualifier.
        viewModel {
            DevConsoleViewModel(
                environment = get(),
                apiBaseUrl = get(named(API_BASE_URL)),
                sessions = get(),
                diagnostics = get(),
                questions = get(),
                queue = get(),
                getNextQuestion = get(),
                skipQuestion = get(),
                castVote = get(),
                getPlayerStats = get(),
                setLike = get(),
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
    val apiBaseUrl = platformApiBaseUrl(environment)
    startKoin {
        appDeclaration()
        modules(platformModule())
        modules(appModules(environment, apiBaseUrl))
    }
}

/**
 * Every module but the platform's, for [environment] reached at [apiBaseUrl], which is the
 * environment's own URL unless the platform put another in its place ([platformApiBaseUrl]).
 * Internal, not private, so a test can load them as [initKoin] does.
 */
internal fun appModules(
    environment: WyrEnvironment,
    apiBaseUrl: String,
): List<Module> =
    listOf(
        module {
            single { environment }
            single(named(API_BASE_URL)) { apiBaseUrl }
        },
        dataModule(environment, apiBaseUrl),
        uiModule,
    )

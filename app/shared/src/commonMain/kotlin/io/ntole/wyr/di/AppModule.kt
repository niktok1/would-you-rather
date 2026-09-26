package io.ntole.wyr.di

import io.ntole.wyr.account.AccountViewModel
import io.ntole.wyr.analytics.AnalyticsSettings
import io.ntole.wyr.analytics.UsageTracker
import io.ntole.wyr.categories.CategoriesViewModel
import io.ntole.wyr.core.data.di.dataModule
import io.ntole.wyr.core.network.analytics.PostHogConfig
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.home.HomeViewModel
import io.ntole.wyr.language.LanguageViewModel
import io.ntole.wyr.play.PlayViewModel
import io.ntole.wyr.submit.SubmitViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import kotlin.time.TimeSource

// Internal, not private, so a test can resolve every ViewModel from the real graph.
internal val uiModule =
    module {
        viewModelOf(::PlayViewModel)
        viewModelOf(::HomeViewModel)
        viewModelOf(::AccountViewModel)
        viewModelOf(::SubmitViewModel)
        viewModelOf(::LanguageViewModel)
        viewModelOf(::CategoriesViewModel)
        // What the analytics time with (CLAUDE.md §8g): how long a question, a screen or the app was shown.
        single<TimeSource.WithComparableMarks> { TimeSource.Monotonic }
        // One for the app's life, as the analytics are: a rotation's new activity finds it.
        single { UsageTracker(analytics = get(), timeSource = get()) }
    }

/**
 * Starts Koin for the server environment [environmentName] names (CLAUDE.md §8e), by
 * [WyrEnvironment.parse]: no name is [WyrEnvironment.LOCAL], and one it does not know stops the app
 * here, before anything has started. [analytics] is the PostHog project the build sends to (§8g), by
 * [PostHogConfig.of]: no key is none, and a host that is none stops the app here too.
 *
 * Called once per process from each platform's entry point, which names the environment it was
 * built or started for: Android's product flavor, the web build's `-Pwyr.env`, desktop's `WYR_ENV`
 * variable, the iOS app's Info.plist; and the analytics each reads from the same place. Neither has a
 * default, so no entry point can leave one out by accident and end up on LOCAL, or send nowhere;
 * `null` is for a build that set none. They are plain strings so that `:core:network` stays off the
 * entry points' classpaths.
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
    analytics: AnalyticsSettings,
    appDeclaration: KoinAppDeclaration = {},
) {
    val environment = WyrEnvironment.parse(environmentName)
    val posthog = PostHogConfig.of(analytics.key, analytics.host, analytics.appVersion)
    startKoin {
        appDeclaration()
        modules(platformModule())
        modules(appModules(environment, posthog))
    }
}

/**
 * Every module but the platform's, for [environment]: the data module sends every request to its
 * URL, and the environment is bound for the screens that show it. Nothing can put another URL in its
 * place, so the server the Account screen names is where requests go. [analytics] is where the
 * analytics go, none sending nothing (§8g). Internal, not private, so a test can load them as
 * [initKoin] does.
 */
internal fun appModules(
    environment: WyrEnvironment,
    analytics: PostHogConfig?,
): List<Module> =
    listOf(
        module { single { environment } },
        dataModule(environment, analytics),
        uiModule,
    )

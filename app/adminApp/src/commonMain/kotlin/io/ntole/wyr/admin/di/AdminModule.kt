package io.ntole.wyr.admin.di

import io.ntole.wyr.admin.moderation.ModerationViewModel
import io.ntole.wyr.core.data.di.moderationDataModule
import io.ntole.wyr.core.network.environment.WyrEnvironment
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Starts Koin for the server environment [environmentName] names (CLAUDE.md §8e), by
 * [WyrEnvironment.parse]: no name is [WyrEnvironment.LOCAL], and one it does not know stops the app
 * here, before anything has started. Returns the environment, for the window to name.
 *
 * Called once per process, by the desktop entry point with `WYR_ENV` and by the page with the
 * constant its build wrote from `-Pwyr.env`.
 */
fun initAdminKoin(environmentName: String?): WyrEnvironment {
    val environment = WyrEnvironment.parse(environmentName)
    startKoin { modules(adminModules(environment)) }
    return environment
}

/**
 * Every module of the app, for [environment]: the moderator's data module sends every request to its
 * URL, and the environment is bound for the screen that shows it. No platform module: the moderator's
 * data module needs no `TokenStorage`, and binds no player session, so nothing this app does can
 * mint a guest or store one (CLAUDE.md §8d, *Moderation*).
 */
fun adminModules(environment: WyrEnvironment): List<Module> =
    listOf(
        module { single { environment } },
        moderationDataModule(environment),
        module { viewModelOf(::ModerationViewModel) },
    )

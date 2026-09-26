package io.ntole.wyr.di

import io.ntole.wyr.about.AppVersion
import io.ntole.wyr.account.AccountViewModel
import io.ntole.wyr.analytics.AnalyticsSettings
import io.ntole.wyr.categories.CategoriesViewModel
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.update.AppUpdate
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LanguageViewModel
import io.ntole.wyr.play.PlayViewModel
import io.ntole.wyr.submit.SubmitViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.Koin
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real data and UI modules, with only the platform bindings stood in for. A binding that is
 * missing only shows when a screen first asks for its ViewModel, and Play is the first screen, so
 * this is the difference between a failing test and an app that dies on launch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppModuleTest {
    @BeforeTest
    fun setUp() {
        // Each ViewModel's init launches on viewModelScope, which runs on Dispatchers.Main.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        // For the tests that start the app's own Koin; stopping one that never started does nothing.
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun `every ViewModel resolves from the real modules`() {
        val koin = koinFor(WyrEnvironment.LOCAL)

        koin.get<PlayViewModel>()
        koin.get<AccountViewModel>()
        koin.get<SubmitViewModel>()
        koin.get<LanguageViewModel>()
        koin.get<CategoriesViewModel>()
        // What App asks for before any screen: whether the server refused this build; and the About
        // screen's version.
        koin.get<AppUpdate>()
        koin.get<AppVersion>()
    }

    @Test
    fun `each environment is the one bound for the screens`() {
        WyrEnvironment.entries.forEach { environment ->
            assertEquals(environment, koinFor(environment).get<WyrEnvironment>())
        }
    }

    @Test
    fun `each environment keeps its own session in the storage the platform shares between them`() =
        runTest {
            val storage = InMemoryTokenStorage()
            val dev = koinFor(WyrEnvironment.DEV, storage).get<SessionStore>()
            val prod = koinFor(WyrEnvironment.PROD, storage).get<SessionStore>()

            dev.write(DEV_SESSION)

            assertEquals(DEV_SESSION, dev.read())
            assertNull(prod.read())
        }

    /**
     * On desktop, iOS and web every environment's build shares one storage (CLAUDE.md §8e), and the
     * language is the player's, not the server's: a build for one shows the language picked in another.
     */
    @Test
    fun `the language is one for the device whatever the environment`() =
        runTest {
            val storage = InMemoryTokenStorage()

            koinFor(WyrEnvironment.DEV, storage).get<LanguageViewModel>().select(Language.ENGLISH)
            testScheduler.advanceUntilIdle()

            WyrEnvironment.entries.forEach { environment ->
                assertEquals(Language.ENGLISH, koinFor(environment, storage).get<LanguageViewModel>().language.value)
            }
        }

    /** The language shares the sessions' storage, so neither may write over the other. */
    @Test
    fun `the language and every session are kept apart in the one storage`() =
        runTest {
            val storage = InMemoryTokenStorage()
            WyrEnvironment.entries.forEach { environment ->
                koinFor(environment, storage).get<SessionStore>().write(DEV_SESSION)
            }

            koinFor(WyrEnvironment.PROD, storage).get<LanguageViewModel>().select(Language.SERBIAN_LATIN)
            testScheduler.advanceUntilIdle()

            WyrEnvironment.entries.forEach { environment ->
                assertEquals(DEV_SESSION, koinFor(environment, storage).get<SessionStore>().read(), "$environment")
            }
            val language = koinFor(WyrEnvironment.PROD, storage).get<LanguageViewModel>().language
            assertEquals(Language.SERBIAN_LATIN, language.value)
        }

    @Test
    fun `initKoin starts the environment each entry point's name names`() {
        mapOf(
            "local" to WyrEnvironment.LOCAL,
            "dev" to WyrEnvironment.DEV,
            "prod" to WyrEnvironment.PROD,
            null to WyrEnvironment.LOCAL,
        ).forEach { (name, environment) ->
            // Only the environment is resolved: the platform's own storage is never built, so nothing
            // of this machine's is read or written.
            initKoin(environmentName = name, analytics = NO_ANALYTICS, build = 10000)

            assertEquals(environment, KoinPlatform.getKoin().get<WyrEnvironment>(), "\"$name\"")
            stopKoin()
        }
    }

    @Test
    fun `an environment name it does not know stops the app before Koin starts`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                initKoin(
                    environmentName = "staging",
                    analytics = NO_ANALYTICS,
                    build = 10000,
                )
            }

        assertTrue("\"staging\"" in failure.message.orEmpty(), failure.message)
        assertNull(KoinPlatform.getKoinOrNull())
    }

    @Test
    fun `a PostHog host that is none stops the app before Koin starts`() {
        val analytics = AnalyticsSettings(key = "phc_key", host = "eu posthog com", appVersion = "1.0")

        val failure =
            assertFailsWith<IllegalArgumentException> { initKoin(environmentName = "dev", analytics, build = 10000) }

        assertTrue("eu posthog com" in failure.message.orEmpty(), failure.message)
        assertNull(KoinPlatform.getKoinOrNull())
    }

    /** No key, as every test and CI build has: the switch is there, and nothing is kept or sent (§8g). */
    @Test
    fun `the analytics bound without a key keep nothing`() {
        val storage = InMemoryTokenStorage()
        val analytics = koinFor(WyrEnvironment.DEV, storage).get<Analytics>()

        analytics.track("app_opened")
        analytics.flush()

        assertTrue(analytics.enabled.value)
        assertNull(storage.read("wyr.analytics.id.dev"))
    }

    private fun koinFor(
        environment: WyrEnvironment,
        storage: TokenStorage = InMemoryTokenStorage(),
    ): Koin {
        val platform = module { single<TokenStorage> { storage } }
        return koinApplication {
            modules(
                listOf(platform) +
                    appModules(environment, analytics = null, version = AppVersion("1.0.0", number = null)),
            )
        }.koin
    }

    private companion object {
        val NO_ANALYTICS = AnalyticsSettings(key = null, host = null, appVersion = "")

        val DEV_SESSION =
            SessionDto(
                playerId = "dev-player",
                accessToken = "dev-access",
                refreshToken = "dev-refresh",
                accessTokenExpiresInSeconds = 900,
            )
    }
}

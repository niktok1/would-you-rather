package io.ntole.wyr.di

import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.dev.DevConsoleViewModel
import io.ntole.wyr.dev.moderation.ModerationConsoleViewModel
import io.ntole.wyr.dev.submission.SubmissionConsoleViewModel
import io.ntole.wyr.play.PlayViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.Koin
import org.koin.core.qualifier.named
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
 * missing only shows when a screen first asks for its ViewModel, and the console is the first
 * screen, so this is the difference between a failing test and an app that dies on launch.
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
        Dispatchers.resetMain()
    }

    @Test
    fun `every ViewModel resolves from the real modules`() {
        val koin = koinFor(WyrEnvironment.LOCAL, WyrEnvironment.LOCAL.apiBaseUrl)

        koin.get<PlayViewModel>()
        koin.get<ModerationConsoleViewModel>()
        koin.get<DevConsoleViewModel>()
        koin.get<SubmissionConsoleViewModel>()
    }

    @Test
    fun `each environment is wired to its own URL`() {
        WyrEnvironment.entries.forEach { environment ->
            val koin = koinFor(environment, environment.apiBaseUrl)

            assertEquals(environment, koin.get<WyrEnvironment>())
            assertEquals(environment.apiBaseUrl, koin.get<String>(named(API_BASE_URL)), environment.name)
            val console = koin.get<DevConsoleViewModel>()
            assertEquals(environment.apiBaseUrl, console.state.value.apiBaseUrl)
        }
    }

    @Test
    fun `a URL the platform puts in the environment's place is the one wired`() {
        val koin = koinFor(WyrEnvironment.DEV, OVERRIDE)

        assertEquals(WyrEnvironment.DEV, koin.get<WyrEnvironment>())
        assertEquals(OVERRIDE, koin.get<String>(named(API_BASE_URL)))
        val console = koin.get<DevConsoleViewModel>()
        assertEquals(OVERRIDE, console.state.value.apiBaseUrl)
    }

    @Test
    fun `an environment name it does not know stops the app before Koin starts`() {
        val failure = assertFailsWith<IllegalArgumentException> { initKoin(environmentName = "staging") }

        assertTrue("\"staging\"" in failure.message.orEmpty(), failure.message)
        assertNull(KoinPlatform.getKoinOrNull())
    }

    private fun koinFor(
        environment: WyrEnvironment,
        apiBaseUrl: String,
    ): Koin {
        val platform = module { single<TokenStorage> { InMemoryTokenStorage() } }
        return koinApplication { modules(listOf(platform) + appModules(environment, apiBaseUrl)) }.koin
    }

    private companion object {
        const val OVERRIDE = "https://wyr.example.com"
    }
}

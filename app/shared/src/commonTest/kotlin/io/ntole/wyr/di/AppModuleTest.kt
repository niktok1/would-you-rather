package io.ntole.wyr.di

import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.SessionStore
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.Koin
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
        val koin = koinFor(WyrEnvironment.LOCAL)

        koin.get<PlayViewModel>()
        koin.get<ModerationConsoleViewModel>()
        koin.get<DevConsoleViewModel>()
        koin.get<SubmissionConsoleViewModel>()
    }

    @Test
    fun `each environment is the one the console shows`() {
        WyrEnvironment.entries.forEach { environment ->
            val koin = koinFor(environment)

            assertEquals(environment, koin.get<WyrEnvironment>())
            val console = koin.get<DevConsoleViewModel>().state.value
            assertEquals(environment, console.environment)
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

    @Test
    fun `an environment name it does not know stops the app before Koin starts`() {
        val failure = assertFailsWith<IllegalArgumentException> { initKoin(environmentName = "staging") }

        assertTrue("\"staging\"" in failure.message.orEmpty(), failure.message)
        assertNull(KoinPlatform.getKoinOrNull())
    }

    private fun koinFor(
        environment: WyrEnvironment,
        storage: TokenStorage = InMemoryTokenStorage(),
    ): Koin {
        val platform = module { single<TokenStorage> { storage } }
        return koinApplication { modules(listOf(platform) + appModules(environment)) }.koin
    }

    private companion object {
        val DEV_SESSION =
            SessionDto(
                playerId = "dev-player",
                accessToken = "dev-access",
                refreshToken = "dev-refresh",
                accessTokenExpiresInSeconds = 900,
            )
    }
}

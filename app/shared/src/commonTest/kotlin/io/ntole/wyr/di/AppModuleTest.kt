package io.ntole.wyr.di

import io.ntole.wyr.core.data.di.dataModule
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.dev.DevConsoleViewModel
import io.ntole.wyr.play.PlayViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
        val platform =
            module {
                single<TokenStorage> { InMemoryTokenStorage() }
                single(named(API_BASE_URL)) { BASE_URL }
            }
        val koin = koinApplication { modules(platform, dataModule(BASE_URL), uiModule) }.koin

        koin.get<PlayViewModel>()
        val console = koin.get<DevConsoleViewModel>()
        assertEquals(BASE_URL, console.state.value.apiBaseUrl)
    }

    private companion object {
        const val BASE_URL = "http://localhost:8080"
    }
}

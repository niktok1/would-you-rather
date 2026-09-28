package io.ntole.wyr.shop

import io.ntole.wyr.analytics.RecordingAnalytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.session.CurrentSession
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.theme.GameThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The theme the game wears, kept on the device for the player who put it on (CLAUDE.md §8d, *The shop*). */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val analytics = RecordingAnalytics()
    private val storage = InMemoryTokenStorage()
    private val session = Sessions("p1")

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which has no implementation under test.
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a device that never put a theme on wears the game's own`() {
        assertEquals(GameThemes.Default, viewModel().theme.value)
    }

    @Test
    fun `a theme put on is worn at once and kept for the player under the environment's key`() =
        runTest(dispatcher) {
            val themes = viewModel()

            themes.wear("OCEAN")
            testScheduler.advanceUntilIdle()

            assertEquals(GameThemes.Ocean, themes.theme.value)
            assertEquals("p1\nOCEAN", storage.read("wyr.theme.dev"))
            assertNull(storage.read("wyr.theme.prod"), "another environment's is its own")
            val applied = analytics.named(AnalyticsEvent.THEME_APPLIED).single()
            assertEquals("OCEAN", applied.properties[AnalyticsProperty.THEME])
        }

    @Test
    fun `the next launch wears it again for the same player alone`() =
        runTest(dispatcher) {
            storage.write("wyr.theme.dev", "p1\nFOREST")

            assertEquals(GameThemes.Forest, viewModel().theme.value)
            assertEquals(GameThemes.Default, viewModel(Sessions("p2")).theme.value, "another player")
            assertEquals(GameThemes.Default, viewModel(Sessions(null)).theme.value, "no session")
        }

    @Test
    fun `another player on the device takes it off and the player back puts it on`() =
        runTest(dispatcher) {
            val themes = viewModel()
            themes.wear("SUNSET")
            testScheduler.advanceUntilIdle()

            session.become("guest2")
            testScheduler.advanceUntilIdle()
            assertEquals(GameThemes.Default, themes.theme.value)

            session.become("p1")
            testScheduler.advanceUntilIdle()
            assertEquals(GameThemes.Sunset, themes.theme.value)
        }

    @Test
    fun `a theme the shop says is not owned comes off and one owned stays`() =
        runTest(dispatcher) {
            val themes = viewModel()
            themes.wear("NEON_NIGHT")
            themes.owned(setOf("NEON_NIGHT"))
            assertEquals(GameThemes.NeonNight, themes.theme.value)

            themes.owned(setOf("OCEAN"))
            testScheduler.advanceUntilIdle()

            assertEquals(GameThemes.Default, themes.theme.value)
            assertEquals("p1\nDEFAULT", storage.read("wyr.theme.dev"))
        }

    @Test
    fun `nothing is put on with no session or for a theme this build has no colours for`() =
        runTest(dispatcher) {
            val themes = viewModel(Sessions(null))
            themes.wear("OCEAN")
            val known = viewModel()
            known.wear("GLITTER")
            testScheduler.advanceUntilIdle()

            assertEquals(GameThemes.Default, themes.theme.value)
            assertEquals(GameThemes.Default, known.theme.value)
            assertNull(storage.read("wyr.theme.dev"))
        }

    private fun viewModel(sessions: Sessions = session) =
        ThemeViewModel(storage, WyrEnvironment.DEV, sessions, analytics)

    private class Sessions(
        private var player: String?,
    ) : CurrentSession {
        private val stored = MutableSharedFlow<String>(extraBufferCapacity = 8)

        override fun current(): String? = player

        override val sessions: Flow<String> = stored

        fun become(next: String) {
            player = next
            stored.tryEmit(next)
        }
    }
}

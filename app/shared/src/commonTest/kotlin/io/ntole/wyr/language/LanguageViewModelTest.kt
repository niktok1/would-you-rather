package io.ntole.wyr.language

import io.ntole.wyr.analytics.RecordingAnalytics
import io.ntole.wyr.analytics.RecordingAnalytics.Recorded
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The language the game is shown in, as the device keeps it (CLAUDE.md §8f). */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val analytics = RecordingAnalytics()

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
    fun `a device that never picked a language shows Serbian Cyrillic`() {
        assertEquals(Language.SERBIAN_CYRILLIC, LanguageViewModel(InMemoryTokenStorage(), analytics).language.value)
    }

    @Test
    fun `a language picked is kept on the device under its own key`() =
        runTest(dispatcher) {
            val storage = InMemoryTokenStorage()
            val viewModel = LanguageViewModel(storage, analytics)

            viewModel.select(Language.SERBIAN_LATIN)
            testScheduler.advanceUntilIdle()

            assertEquals("sr-Latn", storage.read("wyr.language"))
        }

    @Test
    fun `the next launch opens in the language picked last`() =
        runTest(dispatcher) {
            Language.entries.forEach { language ->
                val storage = InMemoryTokenStorage()
                // English first, so each language is a change, Serbian Cyrillic included.
                LanguageViewModel(storage, analytics).apply {
                    select(Language.ENGLISH)
                    select(language)
                }
                testScheduler.advanceUntilIdle()

                assertEquals(language, LanguageViewModel(storage, analytics).language.value)
            }
        }

    /** The write may take a while, a phone's disk being slow, and the screen does not wait for it. */
    @Test
    fun `a language picked shows at once before it is kept`() =
        runTest(dispatcher) {
            val storage = StuckStorage()
            val viewModel = LanguageViewModel(storage, analytics)

            viewModel.select(Language.ENGLISH)

            assertEquals(Language.ENGLISH, viewModel.language.value)
            testScheduler.advanceUntilIdle()
            assertEquals(Language.ENGLISH, viewModel.language.value)
            assertEquals(listOf("en"), storage.writing)
        }

    @Test
    fun `a language that cannot be kept is still this run's`() =
        runTest(dispatcher) {
            val viewModel = LanguageViewModel(FailingStorage(), analytics)

            viewModel.select(Language.ENGLISH)
            testScheduler.advanceUntilIdle()

            assertEquals(Language.ENGLISH, viewModel.language.value)
        }

    @Test
    fun `picking the language shown writes nothing`() =
        runTest(dispatcher) {
            val storage = InMemoryTokenStorage()
            LanguageViewModel(storage, analytics).select(Language.SERBIAN_CYRILLIC)
            testScheduler.advanceUntilIdle()

            assertNull(storage.read("wyr.language"))
        }

    @Test
    fun `a kept language this build does not know shows Serbian Cyrillic`() =
        runTest(dispatcher) {
            val storage = InMemoryTokenStorage()
            storage.write("wyr.language", "de")

            assertEquals(Language.SERBIAN_CYRILLIC, LanguageViewModel(storage, analytics).language.value)
        }

    /** A language picked, by its tag, for the analytics (CLAUDE.md §8g); the one shown already is none. */
    @Test
    fun `a language picked is reported by its tag`() =
        runTest(dispatcher) {
            val viewModel = LanguageViewModel(InMemoryTokenStorage(), analytics)

            viewModel.select(Language.SERBIAN_CYRILLIC)
            viewModel.select(Language.ENGLISH)

            assertEquals(
                listOf(Recorded(AnalyticsEvent.LANGUAGE_CHANGED, mapOf(AnalyticsProperty.LANGUAGE to "en"))),
                analytics.events,
            )
        }

    /** A storage whose every write waits for good, as one stuck on a slow disk would. */
    private class StuckStorage : TokenStorage {
        val writing = mutableListOf<String>()

        override fun read(key: String): String? = null

        override suspend fun write(
            key: String,
            value: String,
        ) {
            writing += value
            CompletableDeferred<Unit>().await()
        }

        override suspend fun remove(key: String) = Unit
    }

    /** A storage whose every write fails, as a full disk's does. */
    private class FailingStorage : TokenStorage {
        override fun read(key: String): String? = null

        override suspend fun write(
            key: String,
            value: String,
        ): Unit = throw IllegalStateException("the disk is full")

        override suspend fun remove(key: String) = Unit
    }
}

package io.ntole.wyr.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What turns each Google service on, and the one line a build without it logs (CLAUDE.md §8a). */
class GoogleServiceSettingsTest {
    /** As every test and CI build runs: nothing is set, so nothing starts. */
    @Test
    fun `a build given no ids has Play Games off and says so in one line`() {
        val none = GoogleServiceSettings()

        assertFalse(none.playGamesOn)
        assertEquals(
            listOf("Play Games is off on this build: no wyr.playgames.appId, wyr.playgames.serverClientId"),
            none.offLines(),
        )
    }

    @Test
    fun `Play Games needs both its ids`() {
        assertFalse(GoogleServiceSettings(playGamesAppId = "123456789").playGamesOn)
        assertFalse(GoogleServiceSettings(playGamesServerClientId = "1-abc.apps.googleusercontent.com").playGamesOn)
        assertFalse(GoogleServiceSettings(playGamesAppId = " ", playGamesServerClientId = "1-abc").playGamesOn)
        assertEquals(
            listOf("Play Games is off on this build: no wyr.playgames.serverClientId"),
            GoogleServiceSettings(playGamesAppId = "123456789").offLines(),
        )

        val both = GoogleServiceSettings("123456789", "1-abc.apps.googleusercontent.com")
        assertTrue(both.playGamesOn)
        assertEquals(emptyList(), both.offLines())
    }
}

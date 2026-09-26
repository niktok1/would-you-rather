package io.ntole.wyr.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What turns each Google service on, and the one line a build without it logs (CLAUDE.md §8a). */
class GoogleServiceSettingsTest {
    /** As every test and CI build runs: nothing is set, so nothing starts. */
    @Test
    fun `a build given no ids has every service off and says so in a line each`() {
        val none = GoogleServiceSettings()

        assertFalse(none.playGamesOn)
        assertFalse(none.pushOn)
        assertEquals(
            listOf(
                "Play Games is off on this build: no wyr.playgames.appId, wyr.playgames.serverClientId",
                "Pushes are off on this build: no wyr.firebase.projectId, wyr.firebase.apiKey, " +
                    "wyr.firebase.senderId, this flavor's wyr.firebase.appId",
            ),
            none.offLines(),
        )
    }

    @Test
    fun `Play Games needs both its ids`() {
        assertFalse(GoogleServiceSettings(playGamesAppId = "123456789").playGamesOn)
        assertFalse(GoogleServiceSettings(playGamesServerClientId = "1-abc.apps.googleusercontent.com").playGamesOn)
        assertFalse(GoogleServiceSettings(playGamesAppId = " ", playGamesServerClientId = "1-abc").playGamesOn)
        assertEquals(
            "Play Games is off on this build: no wyr.playgames.serverClientId",
            GoogleServiceSettings(playGamesAppId = "123456789").offLines().first(),
        )
        assertTrue(GoogleServiceSettings("123456789", "1-abc.apps.googleusercontent.com").playGamesOn)
    }

    @Test
    fun `pushes need all four of Firebase's ids`() {
        val all =
            GoogleServiceSettings(
                firebaseProjectId = "wyr-game",
                firebaseApiKey = "AIzaTest",
                firebaseSenderId = "123456789",
                firebaseAppId = "1:123456789:android:abc",
            )
        assertTrue(all.pushOn)
        assertEquals(
            listOf("Play Games is off on this build: no wyr.playgames.appId, wyr.playgames.serverClientId"),
            all.offLines(),
        )

        assertFalse(all.copy(firebaseAppId = "").pushOn, "a flavor with no app of its own")
        assertEquals(
            "Pushes are off on this build: no this flavor's wyr.firebase.appId",
            all.copy(firebaseAppId = "").offLines().last(),
        )
        assertFalse(all.copy(firebaseApiKey = "").pushOn)
        assertFalse(all.copy(firebaseProjectId = "").pushOn)
        assertFalse(all.copy(firebaseSenderId = "").pushOn)
    }

    @Test
    fun `a build with every id logs nothing`() {
        val every = GoogleServiceSettings("123456789", "1-abc", "wyr-game", "AIzaTest", "123456789", "1:1:android:a")

        assertTrue(every.playGamesOn && every.pushOn)
        assertEquals(emptyList(), every.offLines())
    }
}

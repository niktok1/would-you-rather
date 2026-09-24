package io.ntole.wyr.di

import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the desktop client sends its requests, and what [API_BASE_URL_VARIABLE] may say. */
class DesktopApiBaseUrlTest {
    @Test
    fun `the variable wins over the environment's own URL`() {
        WyrEnvironment.entries.forEach { environment ->
            assertEquals(
                DEPLOYED,
                desktopApiBaseUrl(environment, mapOf(API_BASE_URL_VARIABLE to DEPLOYED)),
                environment.name,
            )
        }
    }

    @Test
    fun `without the variable each environment's own URL is used`() {
        WyrEnvironment.entries.forEach { environment ->
            assertEquals(environment.apiBaseUrl, desktopApiBaseUrl(environment, emptyMap()), environment.name)
        }
        assertEquals("http://localhost:8080", desktopApiBaseUrl(WyrEnvironment.LOCAL, emptyMap()))
    }

    @Test
    fun `a blank value counts as unset`() {
        listOf("", "   ").forEach { blank ->
            assertNull(apiBaseUrlOverride(blank))
            assertEquals(
                WyrEnvironment.DEV.apiBaseUrl,
                desktopApiBaseUrl(WyrEnvironment.DEV, mapOf(API_BASE_URL_VARIABLE to blank)),
            )
        }
    }

    @Test
    fun `an http or https URL of a host is used trimmed`() {
        assertEquals(DEPLOYED, apiBaseUrlOverride(" $DEPLOYED\n"))
        assertEquals("http://192.168.1.20:8080/", apiBaseUrlOverride("http://192.168.1.20:8080/"))
        assertEquals("HTTPS://wyr.example.com", apiBaseUrlOverride("HTTPS://wyr.example.com"))
    }

    @Test
    fun `anything else stops the app at start and names the variable`() {
        listOf(
            "wyr.example.com",
            "localhost:8080",
            "ftp://wyr.example.com",
            "https://",
            "https://wyr.example.com/api",
            "https://wyr.example.com?region=eu",
            "https://wyr.example.com#top",
            "https://player:secret@wyr.example.com",
            "https://wyr example.com",
        ).forEach { value ->
            val failure =
                assertFailsWith<IllegalArgumentException>(value) {
                    desktopApiBaseUrl(WyrEnvironment.LOCAL, mapOf(API_BASE_URL_VARIABLE to value))
                }
            assertTrue(API_BASE_URL_VARIABLE in failure.message.orEmpty(), failure.message)
        }
    }

    private companion object {
        const val DEPLOYED = "https://wyr.example.com"
    }
}

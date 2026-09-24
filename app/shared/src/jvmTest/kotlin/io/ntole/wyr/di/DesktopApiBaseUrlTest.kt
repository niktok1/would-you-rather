package io.ntole.wyr.di

import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Where the desktop client sends its requests, and what [API_BASE_URL_VARIABLE] may say. */
class DesktopApiBaseUrlTest {
    @Test
    fun `the desktop module binds the base URL the variable names`() {
        val koin = koinApplication { modules(desktopModule(mapOf(API_BASE_URL_VARIABLE to DEPLOYED))) }.koin

        assertEquals(DEPLOYED, koin.get<String>(named(API_BASE_URL)))
    }

    @Test
    fun `without the variable the desktop module binds localhost`() {
        val koin = koinApplication { modules(desktopModule(emptyMap())) }.koin

        assertEquals(DevApiBaseUrl.LOCALHOST, koin.get<String>(named(API_BASE_URL)))
    }

    @Test
    fun `a blank value counts as unset`() {
        listOf("", "   ").forEach { blank ->
            assertEquals(DevApiBaseUrl.LOCALHOST, desktopApiBaseUrl(blank))
        }
    }

    @Test
    fun `an http or https URL of a host is used trimmed`() {
        assertEquals(DEPLOYED, desktopApiBaseUrl(" $DEPLOYED\n"))
        assertEquals("http://192.168.1.20:8080/", desktopApiBaseUrl("http://192.168.1.20:8080/"))
        assertEquals("HTTPS://wyr.example.com", desktopApiBaseUrl("HTTPS://wyr.example.com"))
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
            val failure = assertFailsWith<IllegalArgumentException>(value) { desktopApiBaseUrl(value) }
            assertTrue(API_BASE_URL_VARIABLE in failure.message.orEmpty(), failure.message)
        }
    }

    private companion object {
        const val DEPLOYED = "https://wyr.example.com"
    }
}

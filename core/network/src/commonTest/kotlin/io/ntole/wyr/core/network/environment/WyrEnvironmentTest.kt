package io.ntole.wyr.core.network.environment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What a build may name its environment, and where each environment is. */
class WyrEnvironmentTest {
    @Test
    fun `each name parses in any case and trimmed`() {
        mapOf(
            WyrEnvironment.LOCAL to listOf("local", "LOCAL", "Local", " local\n"),
            WyrEnvironment.DEV to listOf("dev", "DEV", "Dev", "\tdev "),
            WyrEnvironment.PROD to listOf("prod", "PROD", "pRoD", " prod"),
        ).forEach { (environment, names) ->
            names.forEach { name -> assertEquals(environment, WyrEnvironment.parse(name), "\"$name\"") }
        }
    }

    @Test
    fun `no name or a blank one is local`() {
        listOf(null, "", "   ", "\n").forEach { name ->
            assertEquals(WyrEnvironment.LOCAL, WyrEnvironment.parse(name), "\"$name\"")
        }
    }

    @Test
    fun `any other name fails and says what it was`() {
        listOf("staging", "production", "development", "loc", "de v", "prod!", "0").forEach { name ->
            val failure = assertFailsWith<IllegalArgumentException>(name) { WyrEnvironment.parse(name) }
            assertTrue("\"$name\"" in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `the deployed environments are the two Render services over https`() {
        assertEquals("https://wyr-server-dev.onrender.com", WyrEnvironment.DEV.apiBaseUrl)
        assertEquals("https://wyr-server.onrender.com", WyrEnvironment.PROD.apiBaseUrl)
    }

    @Test
    fun `local is plain http to the server's default port`() {
        val url = WyrEnvironment.LOCAL.apiBaseUrl

        assertTrue(url.startsWith("http://") && url.endsWith(":8080"), url)
    }

    @Test
    fun `each environment has a name of its own`() {
        assertEquals(listOf("Local", "Dev", "Prod"), WyrEnvironment.entries.map { it.displayName })
    }
}

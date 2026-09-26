package io.ntole.wyr.core.network.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a build's key and host make (CLAUDE.md §8g): none without a key, which is analytics off. */
class PostHogConfigTest {
    @Test
    fun `no key is no analytics`() {
        assertNull(PostHogConfig.of(null, null, "1.0"))
        assertNull(PostHogConfig.of("", "eu.i.posthog.com", "1.0"))
        assertNull(PostHogConfig.of("   ", null, "1.0"))
    }

    @Test
    fun `no host is the EU cloud`() {
        listOf(null, "", "  ").forEach { host ->
            assertEquals("https://eu.i.posthog.com", PostHogConfig.of("phc_key", host, "1.0")?.host, "\"$host\"")
        }
    }

    @Test
    fun `a host is a URL whatever way it is written`() {
        mapOf(
            "https://us.i.posthog.com" to "https://us.i.posthog.com",
            "us.i.posthog.com" to "https://us.i.posthog.com",
            " https://eu.i.posthog.com/ " to "https://eu.i.posthog.com",
            "http://localhost:8000" to "http://localhost:8000",
        ).forEach { (named, host) -> assertEquals(host, PostHogConfig.of("phc_key", named, "1.0")?.host, named) }
    }

    @Test
    fun `the key and the version are trimmed`() {
        val config = PostHogConfig.of(" phc_key ", null, " 1.0 ")

        assertEquals("phc_key", config?.apiKey)
        assertEquals("1.0", config?.appVersion)
        assertEquals(PostHogConfig.UNKNOWN_VERSION, PostHogConfig.of("phc_key", null, " ")?.appVersion)
    }

    @Test
    fun `a host that is none stops the build naming it`() {
        listOf("https://", "eu posthog com").forEach { host ->
            val failure = assertFailsWith<IllegalArgumentException> { PostHogConfig.of("phc_key", host, "1.0") }
            assertTrue(host in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `a key that is none stops the build without naming it`() {
        val failure = assertFailsWith<IllegalArgumentException> { PostHogConfig.of("phc_\"key\"", null, "1.0") }

        assertFalse("phc_" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `the key is never in its text`() {
        assertFalse("phc_secret" in PostHogConfig.of("phc_secret", null, "1.0").toString())
    }
}

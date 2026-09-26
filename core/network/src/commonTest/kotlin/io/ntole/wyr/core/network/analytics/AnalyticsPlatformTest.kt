package io.ntole.wyr.core.network.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The OS every event names, as PostHog's own SDKs name it (CLAUDE.md §8g). */
class AnalyticsPlatformTest {
    @Test
    fun `a browser's agent names its OS and whether it is a phone`() {
        mapOf(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36" to
                ("Android" to MOBILE),
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 Safari/604.1" to
                ("iOS" to MOBILE),
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Safari/605.1.15" to
                ("Mac OS X" to DESKTOP),
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36" to
                ("Windows" to DESKTOP),
            "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 Chrome/126.0 Safari/537.36" to
                ("Chrome OS" to DESKTOP),
            "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0" to ("Linux" to DESKTOP),
            "" to ("Unknown" to DESKTOP),
        ).forEach { (agent, os) -> assertEquals(os, osOfUserAgent(agent), agent) }
    }

    @Test
    fun `a JVM's OS is named as PostHog names it`() {
        assertEquals("Mac OS X", osOfJvmName("Mac OS X"))
        assertEquals("Windows", osOfJvmName("Windows 11"))
        assertEquals("Linux", osOfJvmName("Linux"))
        assertEquals("FreeBSD", osOfJvmName("FreeBSD"))
    }

    @Test
    fun `this platform names itself`() {
        val platform = analyticsPlatform()

        assertTrue(platform.name in setOf("android", "ios", "desktop", "web"), platform.name)
        assertTrue(platform.deviceType in setOf(MOBILE, DESKTOP), platform.deviceType)
    }
}

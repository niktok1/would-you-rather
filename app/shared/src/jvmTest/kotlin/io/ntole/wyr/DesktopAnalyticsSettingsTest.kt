package io.ntole.wyr

import io.ntole.wyr.analytics.AnalyticsSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/** The analytics the desktop client hands `initKoin` (CLAUDE.md §8g), as it names its environment. */
class DesktopAnalyticsSettingsTest {
    @Test
    fun `the key and the host are WYR_POSTHOG_KEY's and WYR_POSTHOG_HOST's`() {
        val variables = mapOf("WYR_POSTHOG_KEY" to "phc_key", "WYR_POSTHOG_HOST" to "us.i.posthog.com")

        assertEquals(
            AnalyticsSettings("phc_key", "us.i.posthog.com", "1.0.0"),
            desktopAnalyticsSettings(variables, mapOf("wyr.app.version" to "1.0.0")),
        )
    }

    @Test
    fun `no variables are no analytics`() {
        assertEquals(AnalyticsSettings(null, null, ""), desktopAnalyticsSettings(emptyMap(), emptyMap()))
    }
}

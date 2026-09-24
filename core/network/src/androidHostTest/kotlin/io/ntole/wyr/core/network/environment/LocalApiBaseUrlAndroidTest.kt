package io.ntole.wyr.core.network.environment

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalApiBaseUrlAndroidTest {
    @Test
    fun `Android reaches a local server through the emulator's alias for the host`() {
        assertEquals("http://10.0.2.2:8080", WyrEnvironment.LOCAL.apiBaseUrl)
    }
}

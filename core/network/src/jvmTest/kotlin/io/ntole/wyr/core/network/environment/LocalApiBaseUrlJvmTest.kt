package io.ntole.wyr.core.network.environment

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalApiBaseUrlJvmTest {
    @Test
    fun `desktop reaches a local server on localhost`() {
        assertEquals("http://localhost:8080", WyrEnvironment.LOCAL.apiBaseUrl)
    }
}

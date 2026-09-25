package io.ntole.wyr.account

import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which server the Account screen names, by the environment the build was made for (CLAUDE.md §8e). */
class AccountScreenTest {
    @Test
    fun `a dev build names the dev server and its URL`() {
        assertEquals("Server: Dev (https://wyr-server-dev.onrender.com)", serverLine(WyrEnvironment.DEV))
    }

    @Test
    fun `a local build names the local server and the URL this platform reaches it at`() {
        assertEquals("Server: Local (${WyrEnvironment.LOCAL.apiBaseUrl})", serverLine(WyrEnvironment.LOCAL))
    }

    @Test
    fun `a prod build names no server`() {
        assertNull(serverLine(WyrEnvironment.PROD))
    }
}

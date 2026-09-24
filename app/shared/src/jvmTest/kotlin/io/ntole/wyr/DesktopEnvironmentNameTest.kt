package io.ntole.wyr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The name the desktop client hands `initKoin`, which parses and checks it (CLAUDE.md §8e). */
class DesktopEnvironmentNameTest {
    @Test
    fun `the name is WYR_ENV's, handed on as it is`() {
        assertEquals("dev", desktopEnvironmentName(mapOf("WYR_ENV" to "dev")))
        assertEquals(" Prod ", desktopEnvironmentName(mapOf("WYR_ENV" to " Prod ")))
    }

    @Test
    fun `no WYR_ENV names none, whatever else is set`() {
        assertNull(desktopEnvironmentName(emptyMap()))
        assertNull(desktopEnvironmentName(mapOf("WYR_ENVIRONMENT" to "prod", "wyr_env" to "prod")))
    }
}

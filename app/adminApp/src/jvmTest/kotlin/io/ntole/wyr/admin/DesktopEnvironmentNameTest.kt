package io.ntole.wyr.admin

import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The name the desktop app hands `initAdminKoin`, which parses and checks it (CLAUDE.md §8e). */
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

    @Test
    fun `each name parses to the server it names, and unset is local`() {
        mapOf(
            mapOf("WYR_ENV" to "dev") to WyrEnvironment.DEV,
            mapOf("WYR_ENV" to " Prod ") to WyrEnvironment.PROD,
            emptyMap<String, String>() to WyrEnvironment.LOCAL,
        ).forEach { (variables, environment) ->
            assertEquals(environment, WyrEnvironment.parse(desktopEnvironmentName(variables)), "$variables")
        }
        assertFailsWith<IllegalArgumentException> {
            WyrEnvironment.parse(
                desktopEnvironmentName(
                    mapOf("WYR_ENV" to "staging"),
                ),
            )
        }
    }
}

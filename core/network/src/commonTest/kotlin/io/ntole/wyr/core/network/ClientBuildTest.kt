package io.ntole.wyr.core.network

import io.ntole.wyr.core.api.WyrApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The build a request names (CLAUDE.md §8b, *Minimum client version*). */
class ClientBuildTest {
    @Test
    fun `a build number is this platform's build`() {
        val build = ClientBuild.of(10203)

        assertEquals(10203, build?.number)
        val platforms =
            setOf(
                WyrApi.ClientPlatform.ANDROID,
                WyrApi.ClientPlatform.IOS,
                WyrApi.ClientPlatform.WEB,
                WyrApi.ClientPlatform.DESKTOP,
            )
        assertTrue(build?.platform in platforms, "${build?.platform}")
    }

    @Test
    fun `no build number or none of at least 1 is no build`() {
        assertNull(ClientBuild.of(null))
        assertNull(ClientBuild.of(0))
        assertNull(ClientBuild.of(-1))
    }

    @Test
    fun `a platform the server does not know is refused`() {
        assertFailsWith<IllegalArgumentException> { ClientBuild("windows", 10000) }
    }
}

package io.ntole.wyr.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The token the desktop app fills its field with at launch (CLAUDE.md §8d, *Moderation*). */
class DesktopAdminTokenTest {
    @Test
    fun `the token is WYR_ADMIN_TOKEN's, handed on as it is`() {
        assertEquals("a".repeat(64), desktopAdminToken(mapOf("WYR_ADMIN_TOKEN" to "a".repeat(64))))
        assertEquals(" token ", desktopAdminToken(mapOf("WYR_ADMIN_TOKEN" to " token ")))
    }

    @Test
    fun `none set, or a blank one, is no token`() {
        assertNull(desktopAdminToken(emptyMap()))
        assertNull(desktopAdminToken(mapOf("WYR_ADMIN_TOKEN" to "  ")))
        assertNull(desktopAdminToken(mapOf("ADMIN_TOKEN" to "token", "wyr_admin_token" to "token")))
    }
}

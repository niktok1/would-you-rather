package io.ntole.wyr.core.domain.moderation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AdminTokenTest {
    @Test
    fun `a pasted token is taken trimmed`() {
        assertEquals("s3cret-token", AdminToken.of("  s3cret-token\n")?.value)
    }

    @Test
    fun `every visible ASCII character may be in a token`() {
        val everyOne = ('!'..'~').joinToString("")

        assertEquals(everyOne, AdminToken.of(everyOne)?.value)
    }

    @Test
    fun `a blank token is no token`() {
        listOf("", " ", "\t\n").forEach { text -> assertNull(AdminToken.of(text), "\"$text\"") }
    }

    @Test
    fun `a token no header could carry is no token`() {
        // The server refuses to boot with any of these, so none can be the one it holds.
        listOf("s3cret token", "s3cret\ttoken", "s3cret\ntoken", "s3cret\u0000", "s3cret\u007F", "s3crét", "s3cret😀")
            .forEach { text -> assertNull(AdminToken.of(text), "\"$text\"") }
    }

    @Test
    fun `a token never shows itself as text`() {
        val token = assertNotNull(AdminToken.of("s3cret-token"))

        listOf(token.toString(), "$token", listOf(token).toString()).forEach { shown ->
            assertFalse("s3cret" in shown, "the token shows in $shown")
        }
    }
}

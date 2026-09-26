package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.moderation.RejectionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The reasons ready to reject a question with: each one the server takes as it reads, in Serbian Cyrillic. */
class ReadyReasonsTest {
    @Test
    fun `each ready reason is one the server takes exactly as it reads`() {
        READY_REASONS.forEach { ready ->
            // Trimmed and on one line already, so what the chip fills in is what the author sees.
            assertEquals(ready, RejectionReason.of(ready)?.value, ready)
        }
    }

    @Test
    fun `the ready reasons are the six the user decided and no two alike`() {
        assertEquals(6, READY_REASONS.size)
        assertEquals(READY_REASONS.size, READY_REASONS.toSet().size)
        assertTrue(READY_REASONS.any { "вера, политика, здравље, сексуалност" in it }, "the question rules")
    }

    @Test
    fun `every ready reason is written in Serbian Cyrillic`() {
        READY_REASONS.forEach { ready ->
            val letters = ready.filter(Char::isLetter)
            assertTrue(letters.isNotEmpty(), ready)
            assertTrue(letters.all { it in SERBIAN_CYRILLIC }, ready)
        }
    }

    private companion object {
        /** The Serbian Cyrillic alphabet's 30 letters, capital and small. */
        val SERBIAN_CYRILLIC: Set<Char> =
            "АБВГДЂЕЖЗИЈКЛЉМНЊОПРСТЋУФХЦЧЏШ".let { capitals -> (capitals + capitals.lowercase()).toSet() }
    }
}

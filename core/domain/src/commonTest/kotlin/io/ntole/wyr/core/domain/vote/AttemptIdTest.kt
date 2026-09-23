package io.ntole.wyr.core.domain.vote

import kotlin.test.Test
import kotlin.test.assertEquals

class AttemptIdTest {
    @Test
    fun `every random attempt is a new one`() {
        // Two taps sharing an attempt would make the second a replay that pays nothing.
        val attempts = List(ATTEMPTS) { AttemptId.random() }

        assertEquals(ATTEMPTS, attempts.toSet().size)
    }

    private companion object {
        const val ATTEMPTS = 1_000
    }
}

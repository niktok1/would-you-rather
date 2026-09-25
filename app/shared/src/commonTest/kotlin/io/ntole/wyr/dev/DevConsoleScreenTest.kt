package io.ntole.wyr.dev

import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.Category
import kotlin.test.Test
import kotlin.test.assertEquals

class DevConsoleScreenTest {
    @Test
    fun `a question shows every category it is filed under`() {
        assertEquals(
            "ETHICS, SUPERPOWERS, OTHER",
            namesOf(setOf(Category.ETHICS, Category.SUPERPOWERS, Category.OTHER)),
        )
    }

    @Test
    fun `likes moved since the outcome name both counts`() {
        assertEquals("likesReceived 0 then, 1 now, not compared", likesMovedNote(0, 1))
    }

    @Test
    fun `the session names its account or a guest`() {
        val stats = PlayerStats("p1", 0, 0, 0, 1, 0, 0)

        assertEquals("guest", accountOf(stats))
        assertEquals("bob_1", accountOf(stats.copy(username = "bob_1")))
        assertEquals("stats not read", accountOf(null))
    }
}

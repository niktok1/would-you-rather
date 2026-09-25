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
    fun `the session names its account or a guest`() {
        val stats = PlayerStats("p1", 0, 0, 0, 1, 0, 0)

        assertEquals("guest", accountOf(stats))
        assertEquals("bob_1", accountOf(stats.copy(username = "bob_1")))
        assertEquals("stats not read", accountOf(null))
    }

    @Test
    fun `a feed filtered to no category reads as every category`() {
        assertEquals("every category", feedFilterOf(emptySet()))
    }

    @Test
    fun `a feed filtered to several categories names each of them`() {
        assertEquals("FOOD, RANDOM", feedFilterOf(setOf(Category.FOOD, Category.RANDOM)))
    }
}

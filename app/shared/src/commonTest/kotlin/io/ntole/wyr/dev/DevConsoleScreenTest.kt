package io.ntole.wyr.dev

import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.Question
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
    fun `the Like button unlikes a question the player likes and likes any other`() {
        val question = Question(id = "q1", optionA = "Fly", optionB = "Swim", categories = setOf(Category.FOOD))

        assertEquals("Unlike", likeActionOf(question.copy(likeCount = 1, likedByMe = true)))
        // Liked by others only: the player's own like is what the button sets.
        assertEquals("Like", likeActionOf(question.copy(likeCount = 4, likedByMe = false)))
        assertEquals("Like", likeActionOf(null))
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

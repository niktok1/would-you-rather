package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.player.PlayerStatsDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerMapperTest {
    @Test
    fun `every stat a screen shows lands in its own field`() {
        // All different, so two fields swapped in the mapping cannot go unnoticed. The rest are the
        // server's alone: no screen shows them, so the domain has no field for them.
        val dto =
            PlayerStatsDto(
                playerId = "p1",
                totalPoints = 7,
                answersGiven = 9,
                questionsAnswered = 5,
                cycle = 2,
                dueThisCycle = 11,
                likesReceived = 3,
                username = "bob_1",
                playGamesLinked = true,
            )

        assertEquals(
            PlayerStats(totalPoints = 7, questionsAnswered = 5, username = "bob_1", playGamesLinked = true),
            dto.toDomain(),
        )
    }

    @Test
    fun `a guest has no username and is not registered`() {
        val guest = PlayerStatsDto(playerId = "p1").toDomain()

        assertNull(guest.username)
        assertFalse(guest.registered)
    }

    /** Play Games registers a player as a username does, with or without one (CLAUDE.md §8a). */
    @Test
    fun `a player linked to Play Games is registered with no username`() {
        val linked = PlayerStatsDto(playerId = "p1", playGamesLinked = true).toDomain()

        assertNull(linked.username)
        assertTrue(linked.registered)
        assertTrue(PlayerStatsDto(playerId = "p1", username = "bob_1").toDomain().registered)
    }
}

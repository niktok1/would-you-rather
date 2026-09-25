package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.player.PlayerStatsDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerMapperTest {
    @Test
    fun `every stat lands in its own field`() {
        // All different, so two fields swapped in the mapping cannot go unnoticed.
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
            )

        assertEquals(
            PlayerStats(
                playerId = "p1",
                totalPoints = 7,
                answersGiven = 9,
                questionsAnswered = 5,
                cycle = 2,
                dueThisCycle = 11,
                likesReceived = 3,
                username = "bob_1",
            ),
            dto.toDomain(),
        )
    }

    @Test
    fun `a guest has no username`() {
        assertNull(PlayerStatsDto(playerId = "p1").toDomain().username)
    }
}

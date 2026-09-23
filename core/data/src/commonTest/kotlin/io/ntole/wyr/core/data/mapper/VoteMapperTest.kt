package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import kotlin.test.Test
import kotlin.test.assertEquals

class VoteMapperTest {
    @Test
    fun `whether the server replayed the vote survives the mapping`() {
        listOf(true, false).forEach { replayed ->
            val dto =
                VoteResultDto(
                    questionId = "q1",
                    yourChoice = OptionSide.B,
                    tally = VoteTallyDto(votesA = 1, votesB = 2),
                    pointsAwarded = 0,
                    totalPoints = 3,
                    replayed = replayed,
                )

            assertEquals(replayed, dto.toDomain().replayed)
        }
    }
}

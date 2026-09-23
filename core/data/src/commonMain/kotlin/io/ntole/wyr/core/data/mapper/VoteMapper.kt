package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto

internal fun VoteResultDto.toDomain(): VoteOutcome =
    VoteOutcome(
        questionId = questionId,
        yourSide = yourChoice.toDomain(),
        tally = tally.toDomain(),
        pointsAwarded = pointsAwarded,
        totalPoints = totalPoints,
        replayed = replayed,
    )

internal fun VoteTallyDto.toDomain(): Tally = Tally(votesA = votesA, votesB = votesB)

internal fun OptionSide.toDomain(): Side =
    when (this) {
        OptionSide.A -> Side.A
        OptionSide.B -> Side.B
    }

internal fun Side.toWire(): OptionSide =
    when (this) {
        Side.A -> OptionSide.A
        Side.B -> OptionSide.B
    }

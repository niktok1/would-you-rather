package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.player.PlayerStatsDto

internal fun PlayerStatsDto.toDomain(): PlayerStats =
    PlayerStats(
        playerId = playerId,
        totalPoints = totalPoints,
        answersGiven = answersGiven,
        questionsAnswered = questionsAnswered,
        cycle = cycle,
        dueThisCycle = dueThisCycle,
        likesReceived = likesReceived,
    )

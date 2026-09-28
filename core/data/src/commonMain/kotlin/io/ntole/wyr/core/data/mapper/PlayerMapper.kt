package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.player.PlayerStatsDto

internal fun PlayerStatsDto.toDomain(): PlayerStats =
    PlayerStats(
        totalPoints = totalPoints,
        questionsAnswered = questionsAnswered,
        username = username,
        playGamesLinked = playGamesLinked,
        submissionCost = submissionCost,
    )

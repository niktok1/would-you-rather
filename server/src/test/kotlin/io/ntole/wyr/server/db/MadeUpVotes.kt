package io.ntole.wyr.server.db

import io.ntole.wyr.core.vote.VoteTallyDto

/**
 * The tally the server reports for [questionId] once players' latest votes are [votesA] for A and
 * [votesB] for B: those on top of the made-up votes a seed starts with, and none for any other
 * question (CLAUDE.md §8d, *Seeds*).
 */
internal fun tallyOf(
    questionId: String,
    votesA: Long,
    votesB: Long,
): VoteTallyDto {
    val (madeUpA, madeUpB) = Seed.SEEDS.toMap()[questionId]?.votes ?: (0 to 0)
    return VoteTallyDto(votesA = madeUpA + votesA, votesB = madeUpB + votesB)
}

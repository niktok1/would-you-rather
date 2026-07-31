package io.ntole.wyr.core.vote

import kotlinx.serialization.Serializable

/**
 * Which of the two options a player picked.
 *
 * Deliberately closed — unlike [io.ntole.wyr.core.question.QuestionCategory] this enum gets no
 * `UNKNOWN` member. A "would you rather" question has exactly two sides by definition, so there
 * is no future value to degrade to and adding one would force meaningless branches on every
 * consumer.
 */
@Serializable
public enum class OptionSide {
    A,
    B,
}

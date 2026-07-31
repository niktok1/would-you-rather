package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * Content category of a question.
 *
 * [UNKNOWN] exists purely for forward compatibility: when the server introduces a new
 * category, already-installed clients must degrade to [UNKNOWN] rather than fail to
 * deserialize the question at all.
 *
 * That guarantee has two halves, and both are required:
 *  1. every property of this type declares a default of [UNKNOWN] (see [QuestionDto]), and
 *  2. the client `Json` instance is configured with `coerceInputValues = true`.
 *
 * Never send [UNKNOWN] over the wire from the server.
 */
@Serializable
public enum class QuestionCategory {
    FOOD,
    LIFESTYLE,
    ETHICS,
    SUPERPOWERS,
    RANDOM,
    UNKNOWN,
}

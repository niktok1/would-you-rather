package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.question.QuestionDto

/**
 * DTO to domain translation for questions.
 *
 * This file is the boundary the architecture rules care about: it is the only place a
 * `QuestionDto` and a `Question` are both in scope (CLAUDE.md §3).
 */
internal fun QuestionDto.toDomain(): Question =
    Question(
        id = id,
        optionA = optionA,
        optionB = optionB,
        categories = categories.toDomainCategories(),
        likeCount = likeCount,
        likedByMe = likedByMe,
    )

/**
 * The ids a DTO lists a question's categories by, as the domain holds them: each once, in the order
 * the server sent them, which is the order of categories (CLAUDE.md §8d, *Categories*). Shared by
 * every DTO that carries a question's categories. An id is kept whether or not this build has read a
 * category of that id: a screen names what it can and shows the rest by id.
 */
internal fun List<String>.toDomainCategories(): Set<String> = toSet()

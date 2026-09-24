package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.question.QuestionCategory
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
        // Each once, in declaration order. A name this build cannot read is OTHER beside the rest, not
        // dropped: the question is filed under something more. An empty list, which the server never
        // sends but a payload without the field decodes as, is OTHER alone: no question is filed under
        // nothing.
        categories =
            categories
                .map { it.toDomain() }
                .sorted()
                .toSet()
                .ifEmpty { setOf(Category.OTHER) },
        answeredBefore = answeredBefore,
    )

internal fun QuestionCategory.toDomain(): Category =
    when (this) {
        QuestionCategory.FOOD -> Category.FOOD

        QuestionCategory.LIFESTYLE -> Category.LIFESTYLE

        QuestionCategory.ETHICS -> Category.ETHICS

        QuestionCategory.SUPERPOWERS -> Category.SUPERPOWERS

        QuestionCategory.RANDOM -> Category.RANDOM

        // The forward-compatibility landing zone: a category this build predates arrives as
        // UNKNOWN (because of QuestionCategoryListSerializer) and plays as an ordinary uncategorised
        // one.
        QuestionCategory.UNKNOWN -> Category.OTHER
    }

/**
 * Domain to wire, for category filters.
 *
 * [Category.OTHER] has no wire equivalent to ask for — it is a local bucket, not a server-side
 * category — so it maps to `null`. No feed is filtered to it: it is not in [Category.selectable],
 * and `QuestionRepository.setCategories` refuses it.
 */
internal fun Category.toWireOrNull(): QuestionCategory? =
    when (this) {
        Category.FOOD -> QuestionCategory.FOOD
        Category.LIFESTYLE -> QuestionCategory.LIFESTYLE
        Category.ETHICS -> QuestionCategory.ETHICS
        Category.SUPERPOWERS -> QuestionCategory.SUPERPOWERS
        Category.RANDOM -> QuestionCategory.RANDOM
        Category.OTHER -> null
    }

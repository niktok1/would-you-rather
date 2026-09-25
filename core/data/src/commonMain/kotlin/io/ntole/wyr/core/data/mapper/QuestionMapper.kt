package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.question.Category
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
 * The categories a question is filed under, as the wire lists them, as the domain holds them: each
 * once, in declaration order. Shared by every DTO that carries a question's categories.
 */
internal fun List<String>.toDomainCategories(): Set<Category> =
    // An id this build cannot name is OTHER beside the rest, not dropped: the question is filed under
    // something more. An empty list, which the server never sends but a payload without the field
    // decodes as, is OTHER alone: no question is filed under nothing.
    map { it.toDomainCategory() }
        .sorted()
        .toSet()
        .ifEmpty { setOf(Category.OTHER) }

/**
 * The domain's category for a category id (CLAUDE.md §8d, *Categories*). Categories are server data
 * now, and until the client lists them itself (`GET /v1/categories`) it knows the first ones by id:
 * ABSURD, which took RANDOM's questions, stands in as [Category.RANDOM], so the picker's Random plays
 * them, and every other id this build cannot name is [Category.OTHER].
 */
internal fun String.toDomainCategory(): Category =
    when (this) {
        FOOD -> Category.FOOD
        LIFESTYLE -> Category.LIFESTYLE
        ETHICS -> Category.ETHICS
        SUPERPOWERS -> Category.SUPERPOWERS
        ABSURD -> Category.RANDOM
        else -> Category.OTHER
    }

/**
 * Domain to wire, for category filters: the id each category goes by, [Category.RANDOM] as ABSURD
 * ([toDomainCategory]).
 *
 * [Category.OTHER] has no wire equivalent to ask for — it is a local bucket, not a server-side
 * category — so it maps to `null`. No feed is filtered to it: it is not in [Category.selectable],
 * and `QuestionRepository.setCategories` refuses it.
 */
internal fun Category.toWireOrNull(): String? =
    when (this) {
        Category.FOOD -> FOOD
        Category.LIFESTYLE -> LIFESTYLE
        Category.ETHICS -> ETHICS
        Category.SUPERPOWERS -> SUPERPOWERS
        Category.RANDOM -> ABSURD
        Category.OTHER -> null
    }

private const val FOOD = "FOOD"
private const val LIFESTYLE = "LIFESTYLE"
private const val ETHICS = "ETHICS"
private const val SUPERPOWERS = "SUPERPOWERS"
private const val ABSURD = "ABSURD"

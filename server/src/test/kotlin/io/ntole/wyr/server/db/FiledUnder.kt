package io.ntole.wyr.server.db

import io.ntole.wyr.core.question.QuestionCategory
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

/**
 * The ids of the questions filed under [category], among others or not, straight from the table
 * rather than through the feed a test checks against it. Must run inside a transaction.
 */
internal fun filedUnder(category: QuestionCategory): List<String> =
    QuestionCategories
        .select(QuestionCategories.questionId)
        .where { QuestionCategories.category eq category.name }
        .map { it[QuestionCategories.questionId] }

/** Every question's categories as stored, by question id, in declaration order. */
internal fun storedCategories(): Map<String, List<QuestionCategory>> =
    QuestionCategories
        .select(QuestionCategories.questionId, QuestionCategories.category)
        .map { it[QuestionCategories.questionId] to QuestionCategory.valueOf(it[QuestionCategories.category]) }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapValues { (_, categories) -> categories.sorted() }

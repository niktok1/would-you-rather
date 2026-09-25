package io.ntole.wyr.server.db

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

/**
 * The ids of the questions filed under [category], among others or not, straight from the table
 * rather than through the feed a test checks against it. Must run inside a transaction.
 */
internal fun filedUnder(category: String): List<String> =
    QuestionCategories
        .select(QuestionCategories.questionId)
        .where { QuestionCategories.category eq category }
        .map { it[QuestionCategories.questionId] }

/** Every question's categories as stored, by question id, in the order of categories. */
internal fun storedCategories(): Map<String, List<String>> =
    QuestionCategories
        .join(Categories, JoinType.INNER, QuestionCategories.category, Categories.id)
        .select(QuestionCategories.questionId, QuestionCategories.category)
        .orderBy(Categories.createdAt to SortOrder.ASC, Categories.id to SortOrder.ASC)
        .map { it[QuestionCategories.questionId] to it[QuestionCategories.category] }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })

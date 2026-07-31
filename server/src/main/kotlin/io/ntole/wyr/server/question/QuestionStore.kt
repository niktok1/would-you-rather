package io.ntole.wyr.server.question

import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.server.db.Questions
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll

object QuestionStore {
    /**
     * Cursor-paged read, ordered by the monotonic `seq` column.
     *
     * The cursor is the last `seq` seen, not an offset: rows inserted while a client pages
     * through cannot shift what it has already been given.
     */
    fun page(
        cursor: Long?,
        limit: Int,
        category: QuestionCategory?,
    ): QuestionPageDto {
        val query = Questions.selectAll()

        cursor?.let { seq -> query.andWhere { Questions.seq greater seq } }
        category?.let { wanted -> query.andWhere { Questions.category eq wanted.name } }

        val rows =
            query
                .orderBy(Questions.seq to SortOrder.ASC)
                .limit(limit)
                .toList()

        return QuestionPageDto(
            questions = rows.map(::toDto),
            // A short page means the end of the catalogue. Reporting a cursor here would make
            // the client fetch one guaranteed-empty page every run-through.
            nextCursor = if (rows.size < limit) null else rows.last()[Questions.seq].toString(),
        )
    }

    fun exists(id: String): Boolean =
        Questions
            .selectAll()
            .where { Questions.id eq id }
            .limit(1)
            .any()

    private fun toDto(row: ResultRow): QuestionDto =
        QuestionDto(
            id = row[Questions.id],
            optionA = row[Questions.optionA],
            optionB = row[Questions.optionB],
            // A category written by an older/newer build than this one still has to read back.
            category =
                runCatching { QuestionCategory.valueOf(row[Questions.category]) }
                    .getOrDefault(QuestionCategory.RANDOM),
        )
}

package io.ntole.wyr.server.question

import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.server.db.Questions
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull

/*
 * Where a question stands, as the wire names it (CLAUDE.md §8d, *Moderation*), from the two columns
 * that hold it: [Questions.status], which a decision writes, and [Questions.retiredAt], which a
 * retirement writes beside an approved status and a restoration clears. A retired question is
 * [QuestionStatus.RETIRED] to every reader of it here, and never stored as such, so a build from before
 * retirement, which reads the status strictly, still reads every row (CLAUDE.md §8b, *Rollbacks*).
 *
 * The two below say the same thing, one of a row read and one in SQL, so a question read through
 * [statusOf] is at the status [standsAt] finds it at, whatever the two columns hold.
 */

/** The status [row], read with [Questions.status] and [Questions.retiredAt], stands at. */
internal fun statusOf(row: ResultRow): QuestionStatus =
    if (row[Questions.retiredAt] != null) QuestionStatus.RETIRED else row[Questions.status]

/**
 * The questions that stand at [status], as [statusOf] reads it. [QuestionStatus.UNKNOWN] is the
 * client's decoding fallback, which no question stands at.
 */
internal fun standsAt(status: QuestionStatus): Op<Boolean> =
    when (status) {
        QuestionStatus.RETIRED -> Questions.retiredAt.isNotNull()
        QuestionStatus.UNKNOWN -> Op.FALSE
        else -> (Questions.status eq status) and Questions.retiredAt.isNull()
    }

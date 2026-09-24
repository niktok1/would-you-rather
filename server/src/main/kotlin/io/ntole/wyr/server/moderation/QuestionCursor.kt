package io.ntole.wyr.server.moderation

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.plugins.requireValidId

/**
 * Where a page of the moderator's question list ended ([ModerationStore.questions]): the last
 * question's place in the list's order, which is its [submittedAt], newest first, and then its [id].
 *
 * A place rather than a count of questions to skip, so a page stays right while questions are
 * stored. Every new question is stored now, so it is newer than any listed and lands before the
 * first page: with an offset it would push the question at the end of a page onto the next one as
 * well, listed twice. And nothing moves a question in the order, since nothing changes when it was
 * stored, so pages never overlap. The [id] breaks a tie between two stored in the same millisecond,
 * as every seed is.
 *
 * On the wire it is [encode]'s text, `<submittedAt>:<id>`, which the client sends back as it was
 * given ([WyrApi.Query.CURSOR]). An id never holds a colon, but [parse] would read one: it splits at
 * the first.
 */
data class QuestionCursor(
    val submittedAt: Long,
    val id: String,
) {
    fun encode(): String = "$submittedAt$SEPARATOR$id"

    companion object {
        private const val SEPARATOR = ':'

        /**
         * The cursor [raw] encodes, or an [ApiFailure.validation] for text the server did not make:
         * no separator, a time that is not a whole number, or an id that is blank or holds a control
         * character, as a request's id may not.
         */
        fun parse(raw: String): QuestionCursor {
            val at = raw.indexOf(SEPARATOR)
            val submittedAt =
                raw.takeIf { at >= 0 }?.substring(0, at)?.toLongOrNull()
                    ?: throw ApiFailure.validation("malformed cursor: $raw")
            val id = raw.substring(at + 1)
            requireValidId(WyrApi.Query.CURSOR, id)
            return QuestionCursor(submittedAt, id)
        }
    }
}

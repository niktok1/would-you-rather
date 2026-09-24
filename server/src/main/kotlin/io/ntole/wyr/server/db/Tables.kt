package io.ntole.wyr.server.db

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.QuestionStatus
import org.jetbrains.exposed.v1.core.Table

/**
 * Timestamps are stored as epoch milliseconds rather than SQL timestamp types. That keeps the
 * schema free of the JDBC/driver timezone differences between H2 and Postgres, and means no
 * extra Exposed date module has to track the kotlinx-datetime API.
 */
object Players : Table("players") {
    val id = varchar("id", 36)
    val createdAt = long("created_at")
    val totalPoints = integer("total_points").default(0)

    /**
     * Every paid answer the player has given, re-answers included and replays not (CLAUDE.md §8d).
     * Kept apart from [totalPoints], which likes are to pay into as well. Only ever moves through
     * `PlayerStore.countAnswer`.
     */
    val answersGiven = integer("answers_given").default(0)

    /** SHA-256 of the current refresh token. The token itself is never stored. */
    val refreshTokenHash = varchar("refresh_token_hash", 64).nullable()
    val refreshTokenExpiresAt = long("refresh_token_expires_at").nullable()

    /**
     * The feed's current pass over the questions (CLAUDE.md §8d), counted from [FIRST_CYCLE]. A
     * question is due while the player has neither answered nor skipped it in this cycle, and the
     * feed starts the next cycle once nothing is due. Only ever moves forward, one at a time, through
     * `PlayerStore.startNextCycle`.
     */
    val currentCycle = integer("current_cycle").default(FIRST_CYCLE)

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = true, refreshTokenHash)
    }

    const val FIRST_CYCLE: Int = 1
}

object Questions : Table("questions") {
    val id = varchar("id", 36)
    val optionA = varchar("option_a", WyrApi.Limits.MAX_OPTION_LENGTH)
    val optionB = varchar("option_b", WyrApi.Limits.MAX_OPTION_LENGTH)
    val category = varchar("category", 32)

    /**
     * The player who submitted the question (CLAUDE.md §8d), or null for a seed, which nobody wrote.
     * The author is served it like any other player (`QuestionStore.servable`).
     */
    val authorPlayerId = varchar("author_player_id", 36).references(Players.id).nullable()

    /**
     * Where the question stands with the moderator. Only an [QuestionStatus.APPROVED] one is ever
     * served. A seed is approved from the start, and a submission starts out
     * [QuestionStatus.PENDING]. Never [QuestionStatus.UNKNOWN], the client's decoding fallback.
     *
     * Read strictly, unlike [category] (`QuestionStore.categoryOf`): a name this build has no
     * [QuestionStatus] for fails the read, and with it the author's whole list
     * (`SubmissionStore.byAuthor`) as a 500. Everything else, the feed and the pending count
     * included, only compares it in SQL and still works. That is deliberate: no status this build
     * knows could stand in truthfully for one it does not, as RANDOM does for a category, and UNKNOWN
     * is never sent. So a new status is a migration (CLAUDE.md §8b), although no column changes: no
     * build may write it until the build a rollback would return to can read it.
     */
    val status = enumerationByName<QuestionStatus>("status", 16)

    /**
     * When the question was stored, submitted or seeded. The one timestamp a question is created
     * with, so a seed has one too although nobody submitted it.
     */
    val submittedAt = long("submitted_at")

    /** When a moderator approved or rejected the question, or null while none has. A seed never was. */
    val reviewedAt = long("reviewed_at").nullable()

    /** The moderator's short reason for rejecting the question, or null for any other. */
    val rejectionReason = varchar("rejection_reason", MAX_REJECTION_REASON_LENGTH).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        // For an author's own questions: the pending ones every submission counts, and the list of
        // all of them. PostgreSQL does not index a foreign key by itself.
        index(isUnique = false, authorPlayerId, status)
    }

    /** Room for the "short reason" of CLAUDE.md §8d. The moderation route decides what it accepts. */
    const val MAX_REJECTION_REASON_LENGTH: Int = 200
}

object Votes : Table("votes") {
    val playerId = varchar("player_id", 36).references(Players.id)
    val questionId = varchar("question_id", 36).references(Questions.id)

    /** The player's latest pick. Re-answering moves it (CLAUDE.md §8d). */
    val side = varchar("side", 1)

    /** When the player first answered. */
    val createdAt = long("created_at")

    /**
     * When the player last answered. Information only: nothing orders or filters by it since the
     * feed moved to cycles ([answeredInCycle]), so it is not indexed.
     */
    val answeredAt = long("answered_at")

    /**
     * The player's [Players.currentCycle] when they last answered, so the question is not due again
     * until a later cycle (CLAUDE.md §8d). A replay leaves it alone, as it does the rest of the row.
     *
     * Not indexed. The feed reads it only off the rows its join finds, and the key already finds
     * those. An index would only cost: every re-answer rewrites this column, and PostgreSQL skips
     * writing index entries for an update (a HOT update) only when no indexed column changes.
     */
    val answeredInCycle = integer("answered_in_cycle")

    /**
     * The client's key for the latest answer (CLAUDE.md §8d). A request carrying it again is a
     * retry of that answer and is replayed. Only the latest is kept, so a retry of an older answer
     * arriving after a newer one counts as a fresh answer.
     */
    val attemptId = varchar("attempt_id", WyrApi.Limits.MAX_ATTEMPT_ID_LENGTH)

    /**
     * One row per player per question, so the tally holds one vote per player. The key is what
     * enforces that — an application-level check would still lose a race between two concurrent
     * first answers from the same player.
     */
    override val primaryKey = PrimaryKey(playerId, questionId)

    init {
        // For the tally, counted on every vote. question_id is the key's second column, so the key
        // cannot find one question's votes, and PostgreSQL does not index a foreign key by itself.
        // With side in it, the count needs only the index.
        index(isUnique = false, questionId, side)
    }
}

/**
 * The questions players have skipped (CLAUDE.md §8d). A skip is kept apart from [Votes] because it
 * is not an answer: it pays nothing, the tally never counts it, and a question skipped but never
 * answered is not `answeredBefore`.
 */
object Skips : Table("skips") {
    val playerId = varchar("player_id", 36).references(Players.id)
    val questionId = varchar("question_id", 36).references(Questions.id)

    /**
     * The latest [Players.currentCycle] the player skipped the question in, so it is not due for the
     * rest of that cycle and is due again in the next. Only ever moves through `SkipStore.skip`.
     */
    val skippedInCycle = integer("skipped_in_cycle")

    /**
     * One row per player per question, holding only the latest skip: a skip from an earlier cycle
     * says nothing about what is due now. The feed's join finds the player's skip by this key, so it
     * needs no index of its own.
     */
    override val primaryKey = PrimaryKey(playerId, questionId)
}

/**
 * Every table the server owns. Schema creation and the test harness's clean-slate drop both read
 * this one list, so a new table belongs here rather than in a `SchemaUtils` call — otherwise it
 * is created in production but survives between tests on a shared database.
 */
val appTables: Array<Table> = arrayOf(Players, Questions, Votes, Skips)

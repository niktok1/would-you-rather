package io.ntole.wyr.server.db

import io.ntole.wyr.core.api.WyrApi
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

    /** SHA-256 of the current refresh token. The token itself is never stored. */
    val refreshTokenHash = varchar("refresh_token_hash", 64).nullable()
    val refreshTokenExpiresAt = long("refresh_token_expires_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = true, refreshTokenHash)
    }
}

object Questions : Table("questions") {
    val id = varchar("id", 36)

    /** Monotonic insertion order. */
    val seq = long("seq").uniqueIndex()

    val optionA = varchar("option_a", MAX_OPTION_LENGTH)
    val optionB = varchar("option_b", MAX_OPTION_LENGTH)
    val category = varchar("category", 32)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    const val MAX_OPTION_LENGTH: Int = 200
}

object Votes : Table("votes") {
    val playerId = varchar("player_id", 36).references(Players.id)
    val questionId = varchar("question_id", 36).references(Questions.id)

    /** The player's latest pick. Re-answering moves it (CLAUDE.md §8d). */
    val side = varchar("side", 1)

    /** When the player first answered. */
    val createdAt = long("created_at")

    /** When the player last answered. The feed loops answered questions back oldest first by this. */
    val answeredAt = long("answered_at")

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
        // One player's answers in the order the feed loops them back.
        index(isUnique = false, playerId, answeredAt)
    }
}

/**
 * Every table the server owns. Schema creation and the test harness's clean-slate drop both read
 * this one list, so a new table belongs here rather than in a `SchemaUtils` call — otherwise it
 * is created in production but survives between tests on a shared database.
 */
val appTables: Array<Table> = arrayOf(Players, Questions, Votes)

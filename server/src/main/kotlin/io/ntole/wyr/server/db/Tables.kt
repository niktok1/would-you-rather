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

    /**
     * What the player's answers earned, plus a point for each like their questions hold now
     * (CLAUDE.md §8c). Only ever moves through `PlayerStore.addPoints`.
     */
    val totalPoints = integer("total_points").default(0)

    /**
     * Every paid answer the player has given, re-answers included and replays not (CLAUDE.md §8d).
     * Kept apart from [totalPoints], which likes pay into as well. Only ever moves through
     * `PlayerStore.countAnswer`.
     */
    val answersGiven = integer("answers_given").default(0)

    /**
     * The mirror (CLAUDE.md §8a, *Sessions*): a copy of the refresh-token columns of the player's
     * session written last, opened or rotated ([Sessions]), for the builds from before sessions, which
     * look a refresh token up here and nowhere else. After a rollback to one, the device each player
     * used last still refreshes. This build refreshes from [Sessions], and spends a token here only
     * where a build without sessions has moved it ([mirroredRefreshTokenHash]).
     *
     * The SHA-256 of the current refresh token, as [Sessions.refreshTokenHash] holds it: the token
     * itself is never stored. Null only for a player minted in the same transaction, before their
     * first session is mirrored.
     */
    val refreshTokenHash = varchar("refresh_token_hash", 64).nullable()
    val refreshTokenExpiresAt = long("refresh_token_expires_at").nullable()

    /**
     * The mirror's copy of the refresh token its session's last rotation displaced, as
     * [Sessions.previousRefreshTokenHash] and the two beside it hold it (CLAUDE.md §8a), so a build
     * from before sessions gives the displaced token the grace this one does. All three are null while
     * the mirrored session has never refreshed.
     */
    val previousRefreshTokenHash = varchar("previous_refresh_token_hash", 64).nullable()
    val previousRefreshTokenExpiresAt = long("previous_refresh_token_expires_at").nullable()
    val previousRefreshTokenRotatedAt = long("previous_refresh_token_rotated_at").nullable()

    /**
     * The [refreshTokenHash] this build last wrote into the mirror, which is the current hash of the
     * session it copied. Only a build with sessions writes it. While the two are equal the mirror is
     * that session's copy. Anything else, null beside a hash included, means a build without sessions
     * has rotated the mirror since, or minted the player there: a rollback, or the build before still
     * serving while a deploy's new instance starts. The next refresh with the token the mirror then
     * holds folds it back into the session it came from, or into a new one for a player minted there
     * (`SessionStore.rotate`).
     */
    val mirroredRefreshTokenHash = varchar("mirrored_refresh_token_hash", 64).nullable()

    /**
     * SHA-256 of the player's recovery secret, which V4 adds beside [Sessions] for the routes that
     * will open a session with it. Nothing writes it yet, so it is null for every player.
     */
    val recoverySecretHash = varchar("recovery_secret_hash", 64).nullable()

    /**
     * The feed's current pass over the questions (CLAUDE.md §8d), counted from [FIRST_CYCLE]. A
     * question is due while the player has neither answered nor skipped it in this cycle, and the
     * feed starts the next cycle once nothing is due. Only ever moves forward, one at a time, through
     * `PlayerStore.startNextCycle`.
     */
    val currentCycle = integer("current_cycle").default(FIRST_CYCLE)

    override val primaryKey = PrimaryKey(id)

    init {
        // A refresh that no session takes looks its token up in the mirror by either hash, and so does
        // every refresh a build without sessions serves. Unique: a hash names one token, and a mirror
        // copies one session.
        index(isUnique = true, refreshTokenHash)
        index(isUnique = true, previousRefreshTokenHash)
        // A recovery looks its player up by it. Unique as a refresh token's hash is.
        index(isUnique = true, recoverySecretHash)
    }

    const val FIRST_CYCLE: Int = 1
}

/**
 * Every session a player has (CLAUDE.md §8a, *Sessions*): one refresh-token family per device, each
 * rotating on its own, so a refresh on one device never touches another's tokens. A guest's mint
 * opens the first (`SessionStore.open`), and V4 opened one for every player who held a refresh token
 * then. Each session opened or rotated is copied into its player's row, the
 * mirror ([Players.refreshTokenHash]). No row is ever deleted: a session whose tokens have expired is
 * dead where it lies, and nothing caps how many a player has.
 */
object Sessions : Table("sessions") {
    val id = varchar("id", 36)
    val playerId = varchar("player_id", 36).references(Players.id)

    /** SHA-256 of the session's current refresh token. The token itself is never stored. */
    val refreshTokenHash = varchar("refresh_token_hash", 64)
    val refreshTokenExpiresAt = long("refresh_token_expires_at")

    /**
     * The refresh token the session's last rotation displaced (CLAUDE.md §8a): its SHA-256, the expiry
     * it had while current, and when it was displaced. A refresh presenting it still succeeds, once,
     * until the next rotation displaces it, and only within a time bound after that rotation where
     * `REFRESH_GRACE_SECONDS` sets one (`SessionStore.rotate`). All three are null for a session that
     * has never refreshed.
     */
    val previousRefreshTokenHash = varchar("previous_refresh_token_hash", 64).nullable()
    val previousRefreshTokenExpiresAt = long("previous_refresh_token_expires_at").nullable()
    val previousRefreshTokenRotatedAt = long("previous_refresh_token_rotated_at").nullable()

    /** When the session was opened. V4 gave the sessions it opened their player's own creation time. */
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        // A refresh looks its token up by either hash, so without an index every refresh, a stranger's
        // guess included, would read the whole table. Unique: a hash names one token, and that token
        // one session. Nothing looks a player's sessions up, so player_id has no index of its own.
        index(isUnique = true, refreshTokenHash)
        index(isUnique = true, previousRefreshTokenHash)
    }
}

object Questions : Table("questions") {
    val id = varchar("id", 36)
    val optionA = varchar("option_a", WyrApi.Limits.MAX_OPTION_LENGTH)
    val optionB = varchar("option_b", WyrApi.Limits.MAX_OPTION_LENGTH)

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
     * Read strictly, unlike a category ([QuestionCategories.category]): a name this build has no
     * [QuestionStatus] for fails the read, and with it the author's whole list
     * (`SubmissionStore.byAuthor`) as a 500. Everything else, the feed, the pending count and the
     * moderator's queue included, only compares it in SQL and still works: the queue reads only rows
     * at the status it asks for (`ModerationStore.queue`). That is deliberate: no status this build
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

    /**
     * When a moderator approved or rejected the question, or null while none has. A seed never was.
     * Only a decision ever sets it (`ModerationStore.decide`).
     */
    val reviewedAt = long("reviewed_at").nullable()

    /**
     * The moderator's short reason for rejecting the question, or null for any other. Only a
     * rejection ever sets it (`ModerationStore.decide`), and an approval clears it.
     */
    val rejectionReason = varchar("rejection_reason", WyrApi.Limits.MAX_REJECTION_REASON_LENGTH).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        // For an author's own questions: the pending ones every submission counts, and the list of
        // all of them. PostgreSQL does not index a foreign key by itself.
        index(isUnique = false, authorPlayerId, status)
        // For the moderator's queue: the questions at one status, oldest first, which is every
        // player's, so the index above cannot find them in order.
        index(isUnique = false, status, submittedAt, id)
    }
}

/**
 * The categories each question is filed under (CLAUDE.md §8d): any number, at least one, one row
 * per question and category. The question and its rows are written in one transaction, so no
 * committed question is ever filed under nothing.
 */
object QuestionCategories : Table("question_categories") {
    val questionId = varchar("question_id", 36).references(Questions.id)

    /**
     * A [io.ntole.wyr.core.question.QuestionCategory] name, never `UNKNOWN`, the client's decoding
     * fallback. Read leniently (`QuestionStore.categoryOf`): a name written by a build that knows a
     * category this one does not still reads back.
     */
    val category = varchar("category", 32)

    /**
     * A question is filed under a category once. The key also finds a question's categories, and
     * whether it has one of those asked for, which is all the feed ever looks up: it asks it of each
     * question it considers, never for every question in a category.
     */
    override val primaryKey = PrimaryKey(questionId, category)
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
 * The likes players hold on questions (CLAUDE.md §8d): a row while the player likes the question,
 * and none once they unlike it, so every row is a like held now, and each is a point to the
 * question's author (`LikeStore.setLiked`). Kept apart from [Votes], since a like is no answer: a
 * player may like a question they have never answered, and liking one changes nothing that is due.
 */
object Likes : Table("likes") {
    val playerId = varchar("player_id", 36).references(Players.id)
    val questionId = varchar("question_id", 36).references(Questions.id)

    /**
     * A player likes a question once. The key is what enforces that, as for a vote: two first likes
     * racing both find no like, and only the key refuses the second.
     */
    override val primaryKey = PrimaryKey(playerId, questionId)

    init {
        // For the like counts, read for every batch the feed serves, every like, and the stats'
        // likes on an author's questions. question_id is the key's second column, so the key cannot
        // find one question's likes, and PostgreSQL does not index a foreign key by itself. With
        // player_id in it, a count and whether the player is among it need only the index.
        index(isUnique = false, questionId, playerId)
    }
}

/**
 * Every table the server owns. The migrations build the schema (`Migrations`), and SchemaDriftTest
 * holds them to this list: a new table belongs here and in a migration, or the build fails. The store
 * tests build their tables straight from it with `SchemaUtils.create`, which that same test shows
 * builds what the migrations do.
 */
val appTables: Array<Table> = arrayOf(Players, Sessions, Questions, QuestionCategories, Votes, Skips, Likes)

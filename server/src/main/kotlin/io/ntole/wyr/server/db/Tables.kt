package io.ntole.wyr.server.db

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.core.report.ReportReason
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

    // The next seven are unused since `feat/simple-accounts`, which dropped the rollback mirror and the
    // recovery secret: no statement names them, so a later migration can drop them (CLAUDE.md §8b,
    // *Rollbacks*). Declared until then, so SchemaDriftTest holds this file to the scripts.

    /** Unused since `feat/simple-accounts` (the mirror of a session's token); drop in a later migration. */
    val refreshTokenHash = varchar("refresh_token_hash", 64).nullable()

    /** Unused since `feat/simple-accounts` (the mirror); drop in a later migration. */
    val refreshTokenExpiresAt = long("refresh_token_expires_at").nullable()

    /** Unused since `feat/simple-accounts` (the mirror); drop in a later migration. */
    val previousRefreshTokenHash = varchar("previous_refresh_token_hash", 64).nullable()

    /** Unused since `feat/simple-accounts` (the mirror); drop in a later migration. */
    val previousRefreshTokenExpiresAt = long("previous_refresh_token_expires_at").nullable()

    /** Unused since `feat/simple-accounts` (the mirror); drop in a later migration. */
    val previousRefreshTokenRotatedAt = long("previous_refresh_token_rotated_at").nullable()

    /** Unused since `feat/simple-accounts` (the mirror's mark); drop in a later migration. */
    val mirroredRefreshTokenHash = varchar("mirrored_refresh_token_hash", 64).nullable()

    /** Unused since `feat/simple-accounts` (the recovery secret); drop in a later migration. */
    val recoverySecretHash = varchar("recovery_secret_hash", 64).nullable()

    /**
     * The feed's current pass over the questions (CLAUDE.md §8d), counted from [FIRST_CYCLE]. A
     * question is due while the player has neither answered nor skipped it in this cycle, and the
     * feed starts the next cycle once nothing is due. Only ever moves forward, one at a time, through
     * `PlayerStore.startNextCycle`.
     */
    val currentCycle = integer("current_cycle").default(FIRST_CYCLE)

    /**
     * The player's username (CLAUDE.md §8b, *Accounts*), stored lower-cased so no two differ only in
     * case, or null for a guest, who has none (V5). Never changes once set.
     */
    val username = varchar("username", WyrApi.Limits.MAX_USERNAME_LENGTH).nullable()

    /**
     * The player's password as `Passwords` keeps it, a salted hash that names its own cost, or null for
     * a guest. Set in the transaction that sets [username], so no player has one without the other.
     * Wider than today's form needs, for a costlier one later.
     */
    val passwordHash = varchar("password_hash", PASSWORD_HASH_LENGTH).nullable()

    /**
     * When a moderator blocked the player from submitting questions (CLAUDE.md §8d, *Moderation*), or
     * null while they may submit (V12). Only `ModerationStore.blockAuthor` sets it, keeping the first
     * block's time, and only `ModerationStore.unblockAuthor` clears it; a submission reads it under the
     * author's row lock (`SubmissionStore.submit`).
     */
    val submissionsBlockedAt = long("submissions_blocked_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        // Unused with their columns (above), and dropped with them.
        index(isUnique = true, refreshTokenHash)
        index(isUnique = true, previousRefreshTokenHash)
        index(isUnique = true, recoverySecretHash)
        // The constraint, not a read, decides two registrations racing for one name, and a login looks
        // the name up by it. NULLs never collide in it, on either engine, so guests never do.
        index(isUnique = true, username)
    }

    private const val PASSWORD_HASH_LENGTH = 255

    const val FIRST_CYCLE: Int = 1
}

/**
 * Every session a player has (CLAUDE.md §8a, *Sessions*): one refresh-token family per device, each
 * rotating on its own, so a refresh on one device never touches another's tokens. A guest's mint
 * opens the first (`SessionStore.open`), and V4 opened one for every player who held a refresh token
 * then. Only a logout deletes a row (`SessionStore.close`): a session whose tokens have expired is dead
 * where it lies, and nothing caps how many a player has.
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
     * served, and only while it is not retired ([retiredAt]). A seed is approved from the start, and a
     * submission starts out [QuestionStatus.PENDING]. Never [QuestionStatus.UNKNOWN], the client's
     * decoding fallback, and never [QuestionStatus.RETIRED]: a retired question stays approved here, and
     * is reported retired from [retiredAt] (`statusOf`), which is what keeps a rollback to a build from
     * before retirement reading every row.
     *
     * Read strictly: a name this build has no [QuestionStatus] for fails the read, and with it the
     * author's whole list (`SubmissionStore.byAuthor`) as a 500. Everything else, the feed, the
     * pending count and the moderator's queue included, only compares it in SQL and still works: the
     * queue reads only rows at the status it asks for (`ModerationStore.queue`). That is deliberate:
     * no status this build knows could stand in truthfully for one it does not, and UNKNOWN is never
     * sent. So a new status is a migration (CLAUDE.md §8b), although no column changes: no build may
     * write it until the build a rollback would return to can read it.
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

    /**
     * When a moderator retired the question, or null while it is not retired (CLAUDE.md §8d,
     * *Moderation*). Only an approved question is ever retired, and its [status] stays
     * [QuestionStatus.APPROVED] beside this, so a build from before V3, which knows no retirement, still
     * reads every row: it would only serve a retired question again. Only `ModerationStore.retire` sets
     * it, and only `ModerationStore.restore` clears it.
     */
    val retiredAt = long("retired_at").nullable()

    /**
     * The points the author paid to submit the question (CLAUDE.md §8c), 0 for a seed and for every
     * question submitted before submitting cost anything (V7). A rejection pays it back
     * (`ModerationStore.reject`) and leaves it here, so what a player's questions cost them is this
     * over those not rejected.
     */
    val submissionCost = integer("submission_cost").default(0)

    /**
     * Made-up votes for each side, which every tally the server reports adds to the players' own
     * (CLAUDE.md §8d, *Seeds*): a seed starts with some (V8, `Seed`), so its split looks like a crowd's
     * from the first answer, and every other question with none. Nothing writes them after that, and
     * they are no player's: a player still holds one vote per question, their latest.
     */
    val baseVotesA = integer("base_votes_a").default(0)
    val baseVotesB = integer("base_votes_b").default(0)

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
 * Every category a question can be filed under (CLAUDE.md §8d, *Categories*): server data, not an
 * enum, so a moderator adds one without a build. V6 wrote the first ones (`Seed.CATEGORIES`), and the
 * seed writes every one its seeds are filed under where it is missing (`Seed.ALL_CATEGORIES`): the
 * later ones everywhere, and V6's into a database with none, as the store tests build. Nothing
 * deletes a category, so an id read once stays a category's.
 */
object Categories : Table("categories") {
    /**
     * What the wire names the category by, forever: 1 to [WyrApi.Limits.MAX_CATEGORY_ID_LENGTH] of
     * `A`-`Z`, `0`-`9` and `_`. The first ones keep the names the enum they replace had on the wire
     * (V6), so a client built before still reads them.
     */
    val id = varchar("id", WyrApi.Limits.MAX_CATEGORY_ID_LENGTH)

    /** The category's name in Serbian, in Cyrillic. */
    val nameSr = varchar("name_sr", WyrApi.Limits.MAX_CATEGORY_NAME_LENGTH)

    /** The category's name in English. */
    val nameEn = varchar("name_en", WyrApi.Limits.MAX_CATEGORY_NAME_LENGTH)

    /**
     * When it was added, which orders the categories, then [id]: every list of them, a question's
     * own included, is oldest first. V6 gave the first ones one millisecond apart, in the order the
     * enum declared them.
     */
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * The categories each question is filed under (CLAUDE.md §8d): any number, at least one, one row
 * per question and category. The question and its rows are written in one transaction, so no
 * committed question is ever filed under nothing.
 */
object QuestionCategories : Table("question_categories") {
    val questionId = varchar("question_id", 36).references(Questions.id)

    /**
     * A [Categories.id]. The foreign key (V6) holds every row to a category the server has, and
     * nothing deletes one, so a row always reads back as one.
     */
    val category = varchar("category", WyrApi.Limits.MAX_CATEGORY_ID_LENGTH).references(Categories.id)

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
 * The reactions players hold on questions (CLAUDE.md §8d, *Reactions*): a row while the player likes
 * or dislikes the question, and none once they take it back, so every row is a reaction held now. A
 * like held is a point to the question's author; a dislike is worth nothing to anybody
 * (`ReactionStore.set`). Kept apart from [Votes], since a reaction is no answer: a player may react to
 * a question they have never answered, and reacting changes nothing that is due.
 */
object Reactions : Table("reactions") {
    val playerId = varchar("player_id", 36).references(Players.id)
    val questionId = varchar("question_id", 36).references(Questions.id)

    /** [Reaction.LIKE] or [Reaction.DISLIKE]; never [Reaction.NONE], which is no row. */
    val reaction = enumerationByName<Reaction>("reaction", 8)

    /**
     * A player holds one reaction per question, a like or a dislike, never both. The key is what
     * enforces that, as for a vote: two first reactions racing both find none, and only the key
     * refuses the second.
     */
    override val primaryKey = PrimaryKey(playerId, questionId)

    init {
        // For the reaction counts, read for every batch the feed serves, every reaction, and the
        // stats' likes on an author's questions. question_id is the key's second column, so the key
        // cannot find one question's reactions, and PostgreSQL does not index a foreign key by itself.
        // With player_id in it, the counts are the index and one look at each row it finds.
        index(isUnique = false, questionId, playerId)
    }
}

/**
 * The reports players have made of questions (CLAUDE.md §8d, *Reports*), for the moderator to look at
 * (`ModerationStore.reports`), until a moderator dismisses a question's. A report also hides its
 * question from the player who made it, in [HiddenQuestions], which a dismissal leaves alone.
 */
object Reports : Table("reports") {
    val playerId = varchar("player_id", 36).references(Players.id)
    val questionId = varchar("question_id", 36).references(Questions.id)

    /** Why, as the player last said: never [ReportReason.UNKNOWN], which a report is refused for. */
    val reason = enumerationByName<ReportReason>("reason", 16)

    /** When the player last reported the question, the reason a repeat replaced included. */
    val reportedAt = long("reported_at")

    /**
     * A player holds one report per question. The key is what enforces it, as for a reaction: two
     * first reports racing both find none, and only the key refuses the second.
     */
    override val primaryKey = PrimaryKey(playerId, questionId)

    init {
        // For the moderator's list, which counts a question's reports, and each reason's, and a
        // dismissal, which deletes them. question_id is the key's second column, so the key cannot find
        // one question's reports.
        index(isUnique = false, questionId, reason)
    }
}

/**
 * The questions each player has hidden from themselves (CLAUDE.md §8d, *Reports*), by reporting one or
 * hiding it: never served to them again, and never due for them (`QuestionStore.visibleTo`). A row is
 * for good: nothing unhides a question, for now.
 */
object HiddenQuestions : Table("hidden_questions") {
    val playerId = varchar("player_id", 36).references(Players.id)
    val questionId = varchar("question_id", 36).references(Questions.id)

    /** A question is hidden once. The feed finds a player's row for a question by this key. */
    override val primaryKey = PrimaryKey(playerId, questionId)
}

/**
 * The authors each player has hidden from themselves (CLAUDE.md §8d, *Reports*): every question an
 * author wrote, those approved later included, is never served to the player again (`QuestionStore.visibleTo`).
 * Named only through a question of theirs, so the author stays anonymous to the player. A row is for good.
 */
object HiddenAuthors : Table("hidden_authors") {
    val playerId = varchar("player_id", 36).references(Players.id)
    val authorPlayerId = varchar("author_player_id", 36).references(Players.id)

    /** An author is hidden once. The feed finds a player's row for an author by this key. */
    override val primaryKey = PrimaryKey(playerId, authorPlayerId)

    init {
        // For the rows that hide an author, found by author when the author's account goes, and for the
        // foreign key's check as their row goes, which PostgreSQL does not index by itself. With
        // player_id in it, as the reactions' is, so it is no copy of the index H2 makes for the key.
        index(isUnique = false, authorPlayerId, playerId)
    }
}

/**
 * Every table the server owns. The migrations build the schema (`Migrations`), and SchemaDriftTest
 * holds them to this list: a new table belongs here and in a migration, or the build fails. The store
 * tests build their tables straight from it with `SchemaUtils.create`, which that same test shows
 * builds what the migrations do.
 */
val appTables: Array<Table> =
    arrayOf(
        Players,
        Sessions,
        Questions,
        Categories,
        QuestionCategories,
        Votes,
        Skips,
        Reactions,
        Reports,
        HiddenQuestions,
        HiddenAuthors,
    )

package io.ntole.wyr.server.db

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.push.PushPlatform
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.server.auth.IdentityProvider
import org.jetbrains.exposed.v1.core.ReferenceOption
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
     * How long the latest answer took, in milliseconds from the question showing to the tap, as the
     * client measured it (`VoteRequest.answerMillis`), or null when it sent none or one outside 0 to
     * [WyrApi.Limits.MAX_ANSWER_MILLIS] (V16). A signal kept for choosing questions to suit a player
     * later (CLAUDE.md §8b, *Personalization*); nothing reads it yet. Follows the latest answer as
     * [side] does, and a replay leaves it alone.
     */
    val answerMillis = long("answer_millis").nullable()

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
 * How many times each of the Home screen's two Play buttons has been tapped, by every player together
 * (CLAUDE.md §8d, *Home picks*): one row per side, which V15 wrote at 0. Nothing adds or deletes a row;
 * only `HomePickStore.pick` moves a count, as an SQL increment.
 */
object HomePicks : Table("home_picks") {
    /** `A` or `B`, an [io.ntole.wyr.core.vote.OptionSide] by name, as a vote's side is. */
    val side = varchar("side", 1)

    /** Every tap of that side's button, a player's repeats included. */
    val picks = long("picks").default(0)

    override val primaryKey = PrimaryKey(side)
}

/**
 * The devices a player's pushes reach (CLAUDE.md §8a, *Push tokens*): one row per Firebase registration
 * token, since a token is one device's, kept under the session that registered it (V17). A token
 * registered again, by its player or another, moves to whoever sent it last (`PushTokenStore.register`),
 * the primary key deciding two registrations racing.
 *
 * Both foreign keys cascade, unlike every other table's: the logout that deletes a session
 * (`SessionStore.close`) deletes its device's tokens with it, and deleting a player deletes theirs, with
 * no store having to know this table is there.
 */
object PushTokens : Table("push_tokens") {
    /** The registration token Firebase gave the device, visible ASCII. */
    val token = varchar("token", WyrApi.Limits.MAX_PUSH_TOKEN_LENGTH)

    val playerId = varchar("player_id", 36).references(Players.id, onDelete = ReferenceOption.CASCADE)

    /** The session that registered it, the device's own: its logout removes the token. */
    val sessionId = varchar("session_id", 36).references(Sessions.id, onDelete = ReferenceOption.CASCADE)

    /** Never [PushPlatform.UNKNOWN], which the route refuses. */
    val platform = enumerationByName<PushPlatform>("platform", 16)

    /** When it was last registered, which keeps a player's newest ones (`PushTokenStore.register`). */
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(token)

    init {
        // For a decision's push and a registration's pruning, which read a player's tokens newest first,
        // and the cascade from a player. PostgreSQL does not index a foreign key by itself. Two columns,
        // not player_id alone, which on H2 would duplicate the index H2 makes for the foreign key.
        // session_id has none: the cascade from a logout reads the table, one row per device with pushes
        // on, which is quick until there are very many.
        index(isUnique = false, playerId, updatedAt)
    }
}

/**
 * The players of other services linked to players here (CLAUDE.md §8a, *Play Games sign-in*): a
 * Google Play Games player, and later a Game Center one, signing in as the player it is linked to
 * (V18). Two constraints decide every race (CLAUDE.md §4): a service's player is linked to one player
 * here (the primary key), and a player here to one of each service's (the unique index).
 *
 * The foreign key cascades, as `push_tokens`' do: deleting a player deletes their links, with no store
 * having to know this table is there. Nothing else deletes one: a link, once made, stands.
 */
object Identities : Table("identities") {
    val provider = enumerationByName<IdentityProvider>("provider", 16)

    /** The service's own id for its player: the Play Games player id Google answered with. */
    val subject = varchar("subject", MAX_SUBJECT_LENGTH)

    val playerId = varchar("player_id", 36).references(Players.id, onDelete = ReferenceOption.CASCADE)

    /** When the link was made. */
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(provider, subject)

    init {
        // A player here is linked to one player of each service. Also what finds a player's links, the
        // stats' among them, and the cascade from a player.
        index(isUnique = true, playerId, provider)
    }

    /** Longest id a service's player can have here: Play Games' are about twenty digits. */
    const val MAX_SUBJECT_LENGTH: Int = 255
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
        HomePicks,
        PushTokens,
        Identities,
    )

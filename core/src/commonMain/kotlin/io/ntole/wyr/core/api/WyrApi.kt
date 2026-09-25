package io.ntole.wyr.core.api

/**
 * The single definition of every path and parameter name on the wire.
 *
 * Both `:server` (route declarations) and `:core:network` (request building) read these, so a
 * path can never drift between the two sides — which is the whole point of the Kotlin-everywhere
 * rule in CLAUDE.md §2. Never hardcode a route string on either side.
 */
public object WyrApi {
    public const val VERSION: String = "v1"

    public object Paths {
        public const val HEALTH: String = "/health"

        /** POST, with no body: mints a guest player with a first session. Limited per client address. */
        public const val AUTH_GUEST: String = "/$VERSION/auth/guest"
        public const val AUTH_REFRESH: String = "/$VERSION/auth/refresh"

        /**
         * GET: the next batch of questions for the player the bearer token names. Requires a
         * session, because the feed is per player (CLAUDE.md §8d): it runs in cycles, serving each
         * question once per cycle in a new random order, and a batch holds only what the player has
         * neither answered nor skipped ([SKIPS]) in the current one, in the categories
         * [Query.CATEGORY] asks for if it does. The exception is a filter with nothing in it due
         * this cycle while something outside it is: its categories are served again within the same
         * cycle, `answeredBefore` on what the player has answered. That includes what the player
         * skipped in them, which is provisional (CLAUDE.md §8b). There is no cursor; asking again is
         * how to get the next batch. Every question comes with how many players like it and whether
         * this one does ([LIKES]), answered or not.
         *
         * POST: submits a question of the session player's own, with a
         * [io.ntole.wyr.core.question.SubmitQuestionRequest], answered 201 with its
         * [io.ntole.wyr.core.question.SubmissionDto]. Requires a session, and earns nothing. The
         * question is stored pending and served to nobody until a moderator approves it, and then to
         * every player, its author included (CLAUDE.md §8d). A player may have at most
         * [Limits.MAX_PENDING_SUBMISSIONS] pending at once, and one more is refused with 409
         * [io.ntole.wyr.core.error.ErrorCode.SUBMISSION_LIMIT]. Options the rules refuse are 422
         * [io.ntole.wyr.core.error.ErrorCode.INVALID_SUBMISSION]; a malformed body, or one naming no
         * category or one that is not real, is 400
         * [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED].
         */
        public const val QUESTIONS: String = "/$VERSION/questions"

        /**
         * Answers a question, with a [io.ntole.wyr.core.vote.VoteRequest], answered with a
         * [io.ntole.wyr.core.vote.VoteResultDto]. Requires a session. A question no player is
         * served, one a moderator has not approved or has retired, is 404
         * [io.ntole.wyr.core.error.ErrorCode.QUESTION_NOT_FOUND], as an unknown one is. An author
         * answers their own like any other player (CLAUDE.md §8d).
         */
        public const val VOTES: String = "/$VERSION/votes"

        /**
         * Skips a question for the rest of the session player's current cycle (CLAUDE.md §8d), with a
         * [io.ntole.wyr.core.question.SkipRequest]. Requires a session. The question comes back in the
         * next cycle, or sooner only to a [QUESTIONS] request filtered to categories with nothing due
         * in them (see there). A skip pays nothing and leaves the tally alone, and skipping again in the
         * same cycle changes nothing. Answered with 204 and no body. A question no player is served
         * is 404, as for [VOTES].
         */
        public const val SKIPS: String = "/$VERSION/skips"

        /**
         * Likes or unlikes a question for the session player (CLAUDE.md §8d), with a
         * [io.ntole.wyr.core.like.LikeRequest], answered with a [io.ntole.wyr.core.like.LikeResultDto].
         * Requires a session. Any question the player is served may be liked, their own included, at
         * any time, whether they have answered it or not.
         *
         * The request sets the like rather than toggling it, so a player holds at most one like per
         * question, and asking for what already holds changes nothing: a retry is harmless. Each like
         * held is a point to the question's author, paid when it is added and taken back when it is
         * removed. A seed has no author, so its likes count and pay nobody. A like does nothing else:
         * it is no answer and no skip, and leaves what is due alone. A question no player is served is
         * 404, as for [VOTES], for an unlike too: the likes a retired question holds stay held, and
         * paid, until it is restored.
         */
        public const val LIKES: String = "/$VERSION/likes"

        /**
         * The stats of the player the bearer token names, as a
         * [io.ntole.wyr.core.player.PlayerStatsDto]. Requires a session. Reading them changes
         * nothing: in particular it never starts the next cycle, which only [QUESTIONS] does.
         */
        public const val ME: String = "/$VERSION/me"

        /**
         * Every question the player the bearer token names has submitted ([QUESTIONS]), whatever its
         * status, newest first, as a [io.ntole.wyr.core.question.SubmissionListDto]. Requires a
         * session. A rejected one carries the moderator's reason, and a retired one says so
         * ([io.ntole.wyr.core.question.QuestionStatus.RETIRED]).
         */
        public const val MY_QUESTIONS: String = "/$VERSION/me/questions"

        /**
         * The moderator's queue (CLAUDE.md §8d, *Moderation*): the submissions waiting for a decision,
         * oldest first, as a [io.ntole.wyr.core.question.SubmissionListDto], so its head is the next
         * to decide and asking again after deciding it gets the rest. [Query.STATUS] lists those of
         * another status instead, in the same order, and [Query.LIMIT] bounds how many, within the
         * feed's bounds. Only players' submissions are listed, never a seed. No author travels: a
         * moderator decides a question by what it says, not by who wrote it. An admin route: needs
         * [Headers.ADMIN_TOKEN].
         */
        public const val ADMIN_SUBMISSIONS: String = "/$VERSION/admin/submissions"

        /**
         * Approves a pending submission, with an [io.ntole.wyr.core.question.ApproveSubmissionRequest],
         * answered with its [io.ntole.wyr.core.question.SubmissionDto] as its author now sees it. From
         * then on it is due for every player, its author included, in whatever cycle each is on
         * (CLAUDE.md §8d). An admin route: needs [Headers.ADMIN_TOKEN].
         *
         * A question that is not pending, whether decided already (by this moderator or another) or a
         * seed, is 409 [io.ntole.wyr.core.error.ErrorCode.ALREADY_DECIDED], and of two decisions racing
         * for one question exactly one is made. An id no question has is 404
         * [io.ntole.wyr.core.error.ErrorCode.QUESTION_NOT_FOUND]. A malformed body, or one naming a
         * category that is not real, is 400 [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED].
         */
        public const val ADMIN_APPROVALS: String = "/$VERSION/admin/approvals"

        /**
         * Rejects a pending submission with a short reason its author sees, with an
         * [io.ntole.wyr.core.question.RejectSubmissionRequest], answered with its
         * [io.ntole.wyr.core.question.SubmissionDto] as its author now sees it. It is served to nobody,
         * ever. Refused as [ADMIN_APPROVALS] refuses, and a reason the rules refuse is 400 too. An
         * admin route: needs [Headers.ADMIN_TOKEN].
         */
        public const val ADMIN_REJECTIONS: String = "/$VERSION/admin/rejections"

        /**
         * Every question, whatever its status, seeds included, newest first, as the moderator sees each
         * one: an [io.ntole.wyr.core.question.AdminQuestionPageDto] of
         * [io.ntole.wyr.core.question.AdminQuestionDto]s, with its tally and like count (CLAUDE.md §8d,
         * *Moderation*). [Query.STATUS] and [Query.CATEGORY] narrow it, each repeated for several and
         * each matching any of its values, none for all; [Query.LIMIT] bounds a page within the feed's
         * bounds, and [Query.CURSOR] asks for the page after the one that sent it. No author travels.
         * An admin route: needs [Headers.ADMIN_TOKEN].
         */
        public const val ADMIN_QUESTIONS: String = "/$VERSION/admin/questions"

        /**
         * Retires an approved question, a seed included, with a
         * [io.ntole.wyr.core.question.RetireQuestionRequest], answered with its
         * [io.ntole.wyr.core.question.AdminQuestionDto], now
         * [io.ntole.wyr.core.question.QuestionStatus.RETIRED] (CLAUDE.md §8d, *Moderation*). From then
         * on it is served to nobody and due for nobody, and a vote, skip, like or unlike of it is 404,
         * until [ADMIN_RESTORATIONS] restores it. Nothing it earned is taken back: its answers' points
         * stay, and its likes stay held and paid. An admin route: needs [Headers.ADMIN_TOKEN].
         *
         * A question that is not approved, a retired one included, is 409
         * [io.ntole.wyr.core.error.ErrorCode.WRONG_STATUS], and of two retirements racing for one
         * question exactly one is made. An id no question has is 404
         * [io.ntole.wyr.core.error.ErrorCode.QUESTION_NOT_FOUND], and a malformed body 400
         * [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED].
         */
        public const val ADMIN_RETIREMENTS: String = "/$VERSION/admin/retirements"

        /**
         * Restores a retired question, with a [io.ntole.wyr.core.question.RestoreQuestionRequest],
         * answered with its [io.ntole.wyr.core.question.AdminQuestionDto], approved again. From then on
         * it is served as it was before it was retired: due for every player who has neither answered
         * nor skipped it in their current cycle (CLAUDE.md §8d). A question that is not retired is 409
         * [io.ntole.wyr.core.error.ErrorCode.WRONG_STATUS]; otherwise refused as [ADMIN_RETIREMENTS]
         * refuses. An admin route: needs [Headers.ADMIN_TOKEN].
         */
        public const val ADMIN_RESTORATIONS: String = "/$VERSION/admin/restorations"
    }

    public object Headers {
        /**
         * Carries the server's admin token on the admin routes, every path under `/v1/admin/`
         * ([Paths.ADMIN_SUBMISSIONS] and the rest). The moderator is whoever holds it, not a role on a
         * player account (CLAUDE.md §8d), so the player's bearer token plays no part and may be sent
         * alongside or not. A header of its own rather than `Authorization`, which carries the player's
         * bearer token and which the client's bearer provider owns.
         *
         * Without it, or with another token, an admin route is 403
         * [io.ntole.wyr.core.error.ErrorCode.FORBIDDEN], checked before anything else about the request.
         * Never 401, which would have a client refresh its player session for nothing. A server with no
         * admin token configured has no admin routes: each is 404, as a path the server does not have.
         */
        public const val ADMIN_TOKEN: String = "X-Admin-Token"
    }

    public object Query {
        /**
         * Max questions to return in one batch, or submissions in [Paths.ADMIN_SUBMISSIONS], or
         * questions in a page of [Paths.ADMIN_QUESTIONS].
         */
        public const val LIMIT: String = "limit"

        /**
         * Optional [io.ntole.wyr.core.question.QuestionCategory] name filter on the feed and on
         * [Paths.ADMIN_QUESTIONS], repeated for several: `?category=FOOD&category=ETHICS` serves the
         * questions filed under any of them, each once, and none is every category (CLAUDE.md §8d).
         * Each value is one name, never a comma-separated list. A value that names no real category,
         * `UNKNOWN` included, is 400 [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED], whatever
         * the others name.
         */
        public const val CATEGORY: String = "category"

        /**
         * A [io.ntole.wyr.core.question.QuestionStatus] name. [Paths.ADMIN_SUBMISSIONS] lists the
         * submissions at the one it names, given at most once, `PENDING` when it is absent.
         * [Paths.ADMIN_QUESTIONS] takes it repeated, `?status=PENDING&status=REJECTED`, and lists the
         * questions at any of them, every question for none. Either way a value that names no real
         * status, `UNKNOWN` included, is 400 [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED], and
         * so is a second value on [Paths.ADMIN_SUBMISSIONS].
         */
        public const val STATUS: String = "status"

        /**
         * Where a page of [Paths.ADMIN_QUESTIONS] starts: the
         * [io.ntole.wyr.core.question.AdminQuestionPageDto.nextCursor] the page before it sent, as it
         * was sent, given at most once. Absent for the first page. One the server did not make, or a
         * second value, is 400 [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED].
         */
        public const val CURSOR: String = "cursor"
    }

    public object Limits {
        public const val DEFAULT_PAGE_SIZE: Int = 20
        public const val MAX_PAGE_SIZE: Int = 100

        /** Longest [io.ntole.wyr.core.vote.VoteRequest.attemptId] the server accepts. A UUID is 36. */
        public const val MAX_ATTEMPT_ID_LENGTH: Int = 64

        /**
         * Longest option a question can have, counted as Kotlin's `String.length` counts, in UTF-16
         * code units: an emoji can take two. A submitted option is measured once trimmed
         * ([io.ntole.wyr.core.question.SubmitQuestionRequest]). Here rather than on the server so a
         * client can check a question against the same number the server's option columns are sized
         * by.
         */
        public const val MAX_OPTION_LENGTH: Int = 200

        /**
         * Longest reason a moderator can give for rejecting a question, the "short reason" its author
         * sees (CLAUDE.md §8d), counted as [MAX_OPTION_LENGTH] counts, and once trimmed. Here rather
         * than on the server so a client can check a reason against the same number the server's
         * column is sized by.
         */
        public const val MAX_REJECTION_REASON_LENGTH: Int = 200

        /**
         * Most submissions one player may have waiting for a moderator at once (CLAUDE.md §8d).
         * Approved and rejected ones do not count, so a decision frees a place.
         */
        public const val MAX_PENDING_SUBMISSIONS: Int = 20

        /**
         * Longest username an account can have, once lower-cased. Here rather than on the server so a
         * client can check a name against the same number the server's column is sized by.
         */
        public const val MAX_USERNAME_LENGTH: Int = 20
    }
}

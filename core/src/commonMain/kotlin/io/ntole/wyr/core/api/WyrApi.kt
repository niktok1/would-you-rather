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
         * POST: registers the player the bearer token names, a guest, as an account (CLAUDE.md §8b,
         * *Accounts*), with a [io.ntole.wyr.core.auth.RegisterRequest], answered with an
         * [io.ntole.wyr.core.auth.AccountDto]. Requires a session. The player keeps everything they
         * have, points and sessions included; only the username and password are new.
         *
         * A username or password the rules refuse is 422
         * [io.ntole.wyr.core.error.ErrorCode.INVALID_USERNAME] or
         * [io.ntole.wyr.core.error.ErrorCode.INVALID_PASSWORD], the username checked first. A username
         * another player has, ignoring case, is 409 [io.ntole.wyr.core.error.ErrorCode.USERNAME_TAKEN],
         * and of two registrations racing for one exactly one gets it. A player registered already is
         * 409 [io.ntole.wyr.core.error.ErrorCode.ALREADY_REGISTERED], whatever the request says: a
         * username and password never change, for now, so a registration sent again after its answer
         * was lost gets this too, and [ME] then names the username. A malformed body is 400
         * [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED]. Limited per player.
         */
        public const val AUTH_REGISTER: String = "/$VERSION/auth/register"

        /**
         * POST: logs in to a registered account on this device (CLAUDE.md §8b, *Accounts*), with a
         * [io.ntole.wyr.core.auth.LoginRequest], answered with a [io.ntole.wyr.core.auth.SessionDto] for
         * a new session of that player, this device's own: the player's other devices stay logged in.
         * Needs no session, and reads none: a bearer token sent beside it plays no part, and the
         * session it names, a guest's, is left as it is.
         *
         * A username and password that name no account are 401
         * [io.ntole.wyr.core.error.ErrorCode.INVALID_LOGIN], alike whether the name is no player's or
         * the password is wrong. A client must send it so that its 401 is never taken for an expired
         * access token, which would refresh and send it again for nothing. A malformed body is 400
         * [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED]. Limited per client address, before
         * anything is read: what bounds guessing a password.
         */
        public const val AUTH_LOGIN: String = "/$VERSION/auth/login"

        /**
         * POST, with no body: ends the session the bearer token was issued for, this device's, and
         * answers 204 (CLAUDE.md §8a, *Sessions*). Its refresh token never works again, the one the
         * grace keeps included, and the player's sessions on other devices are left alone; the client
         * then plays on as a fresh guest ([AUTH_GUEST]). A session already ended is answered 204 too.
         * The access tokens issued to it still work until each expires. A token from a build before
         * tokens named their session is 401 [io.ntole.wyr.core.error.ErrorCode.UNAUTHORIZED], which a
         * refresh answers with one that does. Limited per player.
         */
        public const val AUTH_LOGOUT: String = "/$VERSION/auth/logout"

        /**
         * POST: signs in with Google Play Games Services, with a
         * [io.ntole.wyr.core.auth.PlayGamesSignInRequest], answered with a
         * [io.ntole.wyr.core.auth.SessionDto] for a new session, this device's own (CLAUDE.md §8a, *Play
         * Games sign-in*): the no-click way to an account, beside [AUTH_REGISTER]'s username and
         * password. A bearer token is optional. The server asks Google which Play Games player the code
         * names, and then:
         *  - one already linked to a player here signs in as that player, as a login does, whoever the
         *    bearer names: a guest meeting it switches to it, and leaves its own points behind;
         *  - one linked to no player is linked to the player the bearer names, who keeps everything they
         *    have, unless that player is linked to another Play Games player already, or there is no
         *    bearer, and then to a new player minted for it.
         * Either way the player is registered from then on, as one with a username is: they may submit.
         *
         * A code Google refuses (spent, expired, or another app's) is 422
         * [io.ntole.wyr.core.error.ErrorCode.PLAY_GAMES_CODE_REFUSED], answered by asking Play Games for a
         * new one; Google not answering is 502 [io.ntole.wyr.core.error.ErrorCode.PLAY_GAMES_UNAVAILABLE].
         * Neither changes anything here. An expired bearer token is 401
         * [io.ntole.wyr.core.error.ErrorCode.UNAUTHORIZED] before the code is sent anywhere, so the
         * refreshed retry can send it still unspent. A malformed body is 400
         * [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED]. A server without Play Games configured
         * has no such route: 404. Limited per client address, as a login is.
         */
        public const val AUTH_PLAY_GAMES: String = "/$VERSION/auth/play-games"

        /**
         * GET: the next batch of questions for the player the bearer token names. Requires a
         * session, because the feed is per player (CLAUDE.md §8d): it runs in cycles, serving each
         * question once per cycle in a new random order, and a batch holds only what the player has
         * neither answered nor skipped ([SKIPS]) in the current one, in the categories
         * [Query.CATEGORY] asks for if it does. The exception is a filter with nothing in it due
         * this cycle while something outside it is: its categories are served again within the same
         * cycle, `answeredBefore` on what the player has answered. That includes what the player
         * skipped in them, which is provisional (CLAUDE.md §8b). There is no cursor; asking again is
         * how to get the next batch. Every question comes with how many players like it, how many
         * dislike it and what this one thinks of it ([REACTIONS]), answered or not.
         *
         * POST: submits a question of the session player's own, with a
         * [io.ntole.wyr.core.question.SubmitQuestionRequest], answered 201 with its
         * [io.ntole.wyr.core.question.SubmissionDto]. Requires the session of a registered player: a
         * guest's submission is refused with 403 [io.ntole.wyr.core.error.ErrorCode.ACCOUNT_REQUIRED]
         * before anything it holds is checked. It earns nothing, and costs its author the server's
         * submission cost ([io.ntole.wyr.core.player.PlayerStatsDto.submissionCost], CLAUDE.md §8c),
         * which a rejection pays back. The question is stored
         * pending and served to nobody until a moderator approves it, and then to every player, its
         * author included (CLAUDE.md §8d). A player may have at most
         * [Limits.MAX_PENDING_SUBMISSIONS] pending at once, and one more is refused with 409
         * [io.ntole.wyr.core.error.ErrorCode.SUBMISSION_LIMIT]; an author with fewer points than it
         * costs, with 409 [io.ntole.wyr.core.error.ErrorCode.NOT_ENOUGH_POINTS], nothing stored or
         * taken. Options the rules refuse are 422
         * [io.ntole.wyr.core.error.ErrorCode.INVALID_SUBMISSION]; a malformed body, or one naming no
         * category or an id no category has, is 400
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
         * Likes, dislikes or takes either back for the session player (CLAUDE.md §8d, *Reactions*),
         * with a [io.ntole.wyr.core.reaction.ReactionRequest], answered with a
         * [io.ntole.wyr.core.reaction.ReactionResultDto]. Requires a session. Any question the player is
         * served may be reacted to, their own included, at any time, whether they have answered it or
         * not.
         *
         * The request sets the reaction rather than toggling it, so a player holds at most one per
         * question, a like or a dislike, and asking for what already holds changes nothing: a retry is
         * harmless. Each like held is a point to the question's author, paid when it is added and taken
         * back when it is removed, a dislike that replaces it included; a dislike pays and costs nobody
         * anything. A seed has no author, so its likes count and pay nobody. A reaction does nothing
         * else: it is no answer and no skip, and leaves what is due alone. A question no player is
         * served is 404, as for [VOTES], for taking a reaction back too: the reactions a retired
         * question holds stay held, and its likes paid, until it is restored.
         */
        public const val REACTIONS: String = "/$VERSION/reactions"

        /**
         * Reports a question to the moderator (CLAUDE.md §8d, *Reports*), with a
         * [io.ntole.wyr.core.report.ReportRequest], answered 204. Requires a session. A player holds
         * one report per question, and a report sent again replaces its reason. Reporting also hides
         * the question from the player, as [HIDDEN_QUESTIONS] does, and the moderator dismissing the
         * report leaves it hidden. A question no player is served is 404, as for [VOTES]; a reason that
         * is none, [io.ntole.wyr.core.report.ReportReason.UNKNOWN] or absent, 400
         * [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED]. Limited per player.
         */
        public const val REPORTS: String = "/$VERSION/reports"

        /**
         * Hides a question from the session player for good, with a
         * [io.ntole.wyr.core.report.HideQuestionRequest], answered 204 (CLAUDE.md §8d, *Reports*): the
         * feed never serves it to them again, and it is never due for them. Requires a session. Hiding
         * it again changes nothing. A question no player is served is 404, as for [VOTES]. Limited per
         * player, with [HIDDEN_AUTHORS].
         */
        public const val HIDDEN_QUESTIONS: String = "/$VERSION/hidden-questions"

        /**
         * Hides every question by the author of the one a [io.ntole.wyr.core.report.HideAuthorRequest]
         * names from the session player for good, those approved later included, answered 204
         * (CLAUDE.md §8d, *Reports*). Requires a session. The author stays anonymous. A question nobody
         * wrote, a seed, hides only itself. Refused as [HIDDEN_QUESTIONS] refuses.
         */
        public const val HIDDEN_AUTHORS: String = "/$VERSION/hidden-authors"

        /**
         * GET: every category questions are filed under, with its id and both its names, as a
         * [io.ntole.wyr.core.category.CategoryListDto], oldest first (CLAUDE.md §8d, *Categories*).
         * Needs no session, and reads none: the list is the same for everybody, so a client can have it
         * before it has a player. Limited per client address.
         */
        public const val CATEGORIES: String = "/$VERSION/categories"

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
         * POST, with no body: deletes the account of the player the bearer token names, answered 204
         * (CLAUDE.md §8a, *Deleting an account*). Requires a session. Everything that is theirs goes:
         * their username and password, their sessions on every device, their votes, skips, reactions,
         * reports and hides, and their questions no player is served; their approved questions stay,
         * with nobody as their author, and each like they held is taken back from its author. The client
         * then plays on as a fresh guest ([AUTH_GUEST]). A token of a player deleted already is 401
         * [io.ntole.wyr.core.error.ErrorCode.UNAUTHORIZED], as it is on every route. Limited per player.
         */
        public const val ME_DELETION: String = "/$VERSION/me/deletion"

        /**
         * The Home screen's two Play buttons, each in a card's colour and each starting the game
         * (CLAUDE.md §8d, *Home picks*).
         *
         * GET: how many times each has been tapped, by every player together, as a
         * [io.ntole.wyr.core.home.HomePicksDto]. Needs no session, and reads none: the counts are the
         * same for everybody, so the Home screen can show them before it has a player. Limited per
         * client address.
         *
         * POST: counts one tap of the session player's, with a
         * [io.ntole.wyr.core.home.HomePickRequest], answered with both counts as they stand after it.
         * Requires a session. Every tap counts, a player's repeats included, and none pays or costs
         * anything. A malformed body is 400 [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED].
         * Limited per player.
         */
        public const val HOME_PICKS: String = "/$VERSION/home-picks"

        /**
         * POST: registers this device's push token for the player the bearer token names, with a
         * [io.ntole.wyr.core.push.PushTokenRequest], answered 204 (CLAUDE.md §8a, *Push tokens*).
         * Requires a session, and the token is kept under it: the logout that ends the session
         * ([AUTH_LOGOUT]) removes it. A token is one device's, so registering one another player
         * registered moves it to this one, the latest owner winning. The player's pushes, a
         * moderator's decision on one of their questions for now, reach every device they have
         * registered, the latest ten. A token or platform the rules refuse, or a malformed body, is 400
         * [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED]; a session already ended is 401
         * [io.ntole.wyr.core.error.ErrorCode.UNAUTHORIZED]. Limited per player, with
         * [MY_PUSH_TOKEN_REMOVALS].
         */
        public const val MY_PUSH_TOKENS: String = "/$VERSION/me/push-tokens"

        /**
         * GET: the shop (CLAUDE.md §8d, *The shop*), as a [io.ntole.wyr.core.shop.ShopDto]: every theme on
         * sale with its price and whether the player the bearer token names owns it, and their points.
         * Requires a session, since what is owned is the player's. Limited per player.
         */
        public const val SHOP: String = "/$VERSION/shop"

        /**
         * POST: buys an item of the shop's for the player the bearer token names, with a
         * [io.ntole.wyr.core.shop.PurchaseRequest], answered with the shop as it stands after it, a
         * [io.ntole.wyr.core.shop.ShopDto]. Requires a registered player's session: a guest is 403
         * [io.ntole.wyr.core.error.ErrorCode.ACCOUNT_REQUIRED]. The item's price is taken from the
         * player's points in the purchase's own transaction: fewer points is 409
         * [io.ntole.wyr.core.error.ErrorCode.NOT_ENOUGH_POINTS], an item owned already 409
         * [io.ntole.wyr.core.error.ErrorCode.ALREADY_OWNED], and an id the shop does not sell 404
         * [io.ntole.wyr.core.error.ErrorCode.ITEM_NOT_FOUND]; each takes nothing. Limited per player.
         */
        public const val MY_PURCHASES: String = "/$VERSION/me/purchases"

        /**
         * POST: removes a push token the player the bearer token names registered, with a
         * [io.ntole.wyr.core.push.RemovePushTokenRequest], answered 204 (CLAUDE.md §8a, *Push tokens*),
         * so their pushes no longer reach that device. A token that is not theirs, another player's or
         * none, is left as it is and answered 204 too. Requires a session. Refused as
         * [MY_PUSH_TOKENS] refuses. Limited per player, with [MY_PUSH_TOKENS].
         */
        public const val MY_PUSH_TOKEN_REMOVALS: String = "/$VERSION/me/push-token-removals"

        /**
         * The moderator's queue (CLAUDE.md §8d, *Moderation*): the submissions waiting for a decision,
         * oldest first, as a [io.ntole.wyr.core.question.SubmissionListDto], so its head is the next
         * to decide and asking again after deciding it gets the rest. [Query.STATUS] lists those of
         * another status instead, in the same order, and [Query.LIMIT] bounds how many, within the
         * feed's bounds. Only players' submissions are listed, never a seed. Each names its author by
         * an opaque id alone ([io.ntole.wyr.core.question.SubmissionDto.authorId]), never a username:
         * enough to block an author ([ADMIN_AUTHOR_BLOCKS]), and nothing about who they are. An admin
         * route: needs [Headers.ADMIN_TOKEN].
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
         * [io.ntole.wyr.core.error.ErrorCode.QUESTION_NOT_FOUND]. A malformed body, or one naming an
         * id no category has, is 400 [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED].
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
         * [io.ntole.wyr.core.question.AdminQuestionDto]s, with its tally, like count and dislike count
         * (CLAUDE.md §8d, *Moderation*). [Query.STATUS] and [Query.CATEGORY] narrow it, each repeated for several and
         * each matching any of its values, none for all; [Query.LIMIT] bounds a page within the feed's
         * bounds, and [Query.CURSOR] asks for the page after the one that sent it. Each names its author
         * by an opaque id alone, as the queue does. An admin route: needs [Headers.ADMIN_TOKEN].
         */
        public const val ADMIN_QUESTIONS: String = "/$VERSION/admin/questions"

        /**
         * Retires an approved question, a seed included, with a
         * [io.ntole.wyr.core.question.RetireQuestionRequest], answered with its
         * [io.ntole.wyr.core.question.AdminQuestionDto], now
         * [io.ntole.wyr.core.question.QuestionStatus.RETIRED] (CLAUDE.md §8d, *Moderation*). From then
         * on it is served to nobody and due for nobody, and a vote, skip or reaction to it is 404, until
         * [ADMIN_RESTORATIONS] restores it. Nothing it earned is taken back: its answers' points stay,
         * and its reactions stay held, its likes paid. An admin route: needs [Headers.ADMIN_TOKEN].
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

        /**
         * Adds a category, with an [io.ntole.wyr.core.category.CreateCategoryRequest], answered 201
         * with its [io.ntole.wyr.core.category.CategoryDto] (CLAUDE.md §8d, *Categories*). From then on
         * it is in [CATEGORIES], after every category before it, and a submission, an approval and a
         * filter may name it. An id a category has already is 409
         * [io.ntole.wyr.core.error.ErrorCode.CATEGORY_EXISTS]; names or an id the rules refuse, or a
         * malformed body, 400 [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED]. Nothing deletes
         * a category. An admin route: needs [Headers.ADMIN_TOKEN].
         */
        public const val ADMIN_CATEGORIES: String = "/$VERSION/admin/categories"

        /**
         * Sets both names of a category, with an [io.ntole.wyr.core.category.RenameCategoryRequest],
         * answered with its [io.ntole.wyr.core.category.CategoryDto] as it now stands. Its id never
         * changes. An id no category has is 404
         * [io.ntole.wyr.core.error.ErrorCode.CATEGORY_NOT_FOUND]; names the rules refuse, or a
         * malformed body, 400 [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED]. An admin route:
         * needs [Headers.ADMIN_TOKEN].
         */
        public const val ADMIN_CATEGORY_RENAMES: String = "/$VERSION/admin/category-renames"

        /**
         * The reported questions, most reported first, then the most lately reported, as an
         * [io.ntole.wyr.core.report.AdminReportListDto] (CLAUDE.md §8d, *Reports*): each question as the
         * list of every question shows it, with how many players report it and how many give each
         * reason. [Query.LIMIT] bounds how many, within the feed's bounds; there is no cursor, since a
         * moderator works from the head, as in the queue, and a dismissal takes a question off it. A
         * question stays listed whatever it stands at, retired included, until its reports are
         * dismissed. An admin route: needs [Headers.ADMIN_TOKEN].
         */
        public const val ADMIN_REPORTS: String = "/$VERSION/admin/reports"

        /**
         * Clears every report of a question, with an [io.ntole.wyr.core.report.DismissReportsRequest],
         * answered 204: it leaves [ADMIN_REPORTS] until a player reports it again. The question stays
         * as it stands, and hidden from each player who reported it. A question with no reports is
         * answered 204 too; an id no question has is 404
         * [io.ntole.wyr.core.error.ErrorCode.QUESTION_NOT_FOUND]. An admin route: needs
         * [Headers.ADMIN_TOKEN].
         */
        public const val ADMIN_REPORT_DISMISSALS: String = "/$VERSION/admin/report-dismissals"

        /**
         * Blocks an author from submitting, with an [io.ntole.wyr.core.author.BlockAuthorRequest], and
         * rejects every submission of theirs still pending for its one reason, paying each one's cost
         * back as any rejection does (CLAUDE.md §8c), answered with an
         * [io.ntole.wyr.core.author.AuthorBlockDto]. From then on their every submission is 403
         * [io.ntole.wyr.core.error.ErrorCode.SUBMISSIONS_BLOCKED]. Their approved questions stay as they
         * are. Blocking a blocked author again rejects whatever is pending and changes nothing else. An
         * id no player has is 404 [io.ntole.wyr.core.error.ErrorCode.AUTHOR_NOT_FOUND]; a reason the
         * rules refuse, or a malformed body, 400. An admin route: needs [Headers.ADMIN_TOKEN].
         */
        public const val ADMIN_AUTHOR_BLOCKS: String = "/$VERSION/admin/author-blocks"

        /**
         * Lets a blocked author submit again, with an [io.ntole.wyr.core.author.UnblockAuthorRequest],
         * answered with an [io.ntole.wyr.core.author.AuthorBlockDto]. What the block rejected stays
         * rejected. Unblocking an author who is not blocked changes nothing. Refused as
         * [ADMIN_AUTHOR_BLOCKS] refuses. An admin route: needs [Headers.ADMIN_TOKEN].
         */
        public const val ADMIN_AUTHOR_UNBLOCKS: String = "/$VERSION/admin/author-unblocks"

        /**
         * Deletes a player's account on their request, with an
         * [io.ntole.wyr.core.player.DeleteAccountRequest] naming them by username or by account id,
         * answered 204 (CLAUDE.md §8a, *Deleting an account*): the same deletion as [ME_DELETION], for a
         * player who asked for it by email. Their sessions die with it, so their next request is 401. A
         * name or an id no player has is 404 [io.ntole.wyr.core.error.ErrorCode.PLAYER_NOT_FOUND];
         * neither or both named, or a malformed body, 400. An admin route: needs [Headers.ADMIN_TOKEN].
         */
        public const val ADMIN_ACCOUNT_DELETIONS: String = "/$VERSION/admin/account-deletions"
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

        /**
         * Which client sent the request, one of [ClientPlatform]'s names, sent with [CLIENT_VERSION] on
         * every request of the game's (CLAUDE.md §8b, *Minimum client version*). With the two, a server
         * that has a minimum build for the platform refuses an older build with 426
         * [io.ntole.wyr.core.error.ErrorCode.UPGRADE_REQUIRED] before anything else, every path but
         * [Paths.HEALTH]. A request without them, the moderation app's or a build's from before them,
         * is never refused for its build, and neither is a platform with no minimum, nor a version that
         * is no whole number.
         */
        public const val CLIENT_PLATFORM: String = "X-Client-Platform"

        /** The client's build number, a whole number that grows with every release: see [CLIENT_PLATFORM]. */
        public const val CLIENT_VERSION: String = "X-Client-Version"
    }

    /** What [Headers.CLIENT_PLATFORM] names, in lower case, as a client sends it. */
    public object ClientPlatform {
        public const val ANDROID: String = "android"
        public const val IOS: String = "ios"
        public const val WEB: String = "web"
        public const val DESKTOP: String = "desktop"
    }

    public object Query {
        /**
         * Max questions to return in one batch, or submissions in [Paths.ADMIN_SUBMISSIONS], or
         * questions in a page of [Paths.ADMIN_QUESTIONS].
         */
        public const val LIMIT: String = "limit"

        /**
         * Optional category filter on the feed and on [Paths.ADMIN_QUESTIONS], by category id,
         * repeated for several: `?category=FOOD&category=ETHICS` serves the questions filed under any
         * of them, each once, and none is every category (CLAUDE.md §8d). Each value is one id, never a
         * comma-separated list. A value that is no category's id is 400
         * [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED], whatever the others name.
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
         * Longest [io.ntole.wyr.core.vote.VoteRequest.answerMillis] the server keeps, 10 minutes: a
         * longer one says the question sat on screen, not how long the player thought, and is kept as
         * none.
         */
        public const val MAX_ANSWER_MILLIS: Long = 10L * 60L * 1_000L

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
         * Longest id a category can have. An id is 1 to this many of `A`-`Z`, `0`-`9` and `_`
         * (CLAUDE.md §8d, *Categories*), as the first ones are: `FOOD`, `LIFESTYLE`, `ETHICS`,
         * `SUPERPOWERS` and `ABSURD`. Here rather than on the server so a client can check an id
         * against the same number the server's columns are sized by.
         */
        public const val MAX_CATEGORY_ID_LENGTH: Int = 32

        /**
         * Longest name a category can have, in either language, counted as [MAX_OPTION_LENGTH]
         * counts. Here rather than on the server so a client can check a name against the same
         * number the server's columns are sized by.
         */
        public const val MAX_CATEGORY_NAME_LENGTH: Int = 40

        /**
         * Most submissions one player may have waiting for a moderator at once (CLAUDE.md §8d).
         * Approved and rejected ones do not count, so a decision frees a place.
         */
        public const val MAX_PENDING_SUBMISSIONS: Int = 20

        /**
         * What submitting a question costs its author, in points, when the server's `SUBMISSION_COST`
         * is unset (CLAUDE.md §8c): 1, until the release sets 50. Only the default: the server charges
         * its own setting and names it in [io.ntole.wyr.core.player.PlayerStatsDto.submissionCost], which
         * defaults to this, and a client falls back to it until it has read that.
         */
        public const val SUBMISSION_COST: Int = 1

        /**
         * Longest id an item of the shop's can have ([io.ntole.wyr.core.shop.ShopThemeDto.id]): 1 to this
         * many of `A`-`Z`, `0`-`9` and `_`, as a category's id. Here so the server's column and a client's
         * check share one number.
         */
        public const val MAX_SHOP_ITEM_ID_LENGTH: Int = 32

        /**
         * Shortest username an account can have, once lower-cased
         * ([io.ntole.wyr.core.auth.RegisterRequest]). Here, with the rest of the account's limits, so
         * a client can check what the player types against the numbers the server checks it by.
         */
        public const val MIN_USERNAME_LENGTH: Int = 3

        /**
         * Longest username an account can have, once lower-cased. Here rather than on the server so a
         * client can check a name against the same number the server's column is sized by.
         */
        public const val MAX_USERNAME_LENGTH: Int = 20

        /** Shortest password an account can have, counted as [MAX_OPTION_LENGTH] counts. */
        public const val MIN_PASSWORD_LENGTH: Int = 6

        /** Longest password an account can have, counted as [MAX_OPTION_LENGTH] counts. */
        public const val MAX_PASSWORD_LENGTH: Int = 128

        /**
         * Longest push token the server keeps ([io.ntole.wyr.core.push.PushTokenRequest]), in
         * characters, each visible ASCII. A Firebase token is about 160.
         */
        public const val MAX_PUSH_TOKEN_LENGTH: Int = 1024

        /**
         * Longest server auth code a Play Games sign-in may carry
         * ([io.ntole.wyr.core.auth.PlayGamesSignInRequest]), in characters, each visible ASCII. One is
         * about a hundred.
         */
        public const val MAX_SERVER_AUTH_CODE_LENGTH: Int = 2048
    }
}

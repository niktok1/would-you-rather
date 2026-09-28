package io.ntole.wyr.server.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * [requests] for one key, a player or a client's address, in a window of [per] that starts at the
 * key's first request, or its first after the last window ended, and refills whole when it ends: a
 * fixed window, as Ktor's limiter counts. So up to twice [requests] can pass in moments, spent just
 * before one window ends and again just after the next begins, though over any longer span the
 * average holds.
 */
data class RequestBudget(
    val requests: Int,
    val per: Duration,
)

/**
 * What one client may send (CLAUDE.md §8b, *Rate limiting*): a budget for each group of routes, spent
 * apart from every other group's. Registrations, logouts, the feed, votes, skips, reactions, submissions,
 * reports, hides, deletions, home picks, push tokens, the shop, purchases and the two reads of the
 * player's own are per player, so players behind one address do not share them; the rest, whose caller
 * has no session to name, or needs none, per client address.
 *
 * Each is overridable by the environment variable [fromEnvironment] names, a count per the period the
 * name ends in. The periods are fixed.
 */
data class RateLimits(
    /**
     * `POST /v1/auth/guest`, per address: what a script minting guests to farm with can get. Room for
     * the many players one address can stand for, a mobile carrier's shared address or a school's
     * Wi-Fi, each new install minting one.
     */
    val guests: RequestBudget,
    /** `POST /v1/auth/refresh`, per address. A player refreshes about once per access token. */
    val refreshes: RequestBudget,
    /**
     * `POST /v1/auth/login`, per address: what bounds guessing a password, and the hashes a login costs.
     * Enough for a room of players behind one address all logging in at once.
     */
    val logins: RequestBudget,
    /**
     * `POST /v1/auth/play-games`, per address, as the caller may have no session: each sign-in costs two
     * calls to Google.
     */
    val playGames: RequestBudget,
    /**
     * `POST /v1/auth/register`. A player registers once, but a name they want may be taken, and every
     * try but one that breaks a rule costs a password hash.
     */
    val registrations: RequestBudget,
    /** `POST /v1/auth/logout`. */
    val logouts: RequestBudget,
    /** `GET /v1/questions`. */
    val feed: RequestBudget,
    /** `POST /v1/votes`. Every re-answer pays (CLAUDE.md §8d), so this bounds what one player can farm. */
    val votes: RequestBudget,
    /** `POST /v1/skips`. */
    val skips: RequestBudget,
    /** `POST /v1/reactions`. */
    val reactions: RequestBudget,
    /** `POST /v1/questions`. The pending cap still applies within it. */
    val submissions: RequestBudget,
    /** `POST /v1/reports`. A player reports what they come across, a few at most. */
    val reports: RequestBudget,
    /** `POST /v1/hidden-questions` and `POST /v1/hidden-authors` together. */
    val hides: RequestBudget,
    /** `POST /v1/me/deletion`. A player deletes their account once; a retry after a lost answer is 401. */
    val deletions: RequestBudget,
    /** `GET /v1/me`. */
    val stats: RequestBudget,
    /** `GET /v1/me/questions`. */
    val mySubmissions: RequestBudget,
    /**
     * `GET /v1/categories`, per address, since it needs no session. A client reads the list when it
     * starts and when a picker opens, and players behind one address share this.
     */
    val categories: RequestBudget,
    /**
     * `GET /v1/home-picks`, per address, since it needs no session. The Home screen reads the counts each
     * time it is shown, and players behind one address share this.
     */
    val homePickCounts: RequestBudget,
    /** `POST /v1/home-picks`. Every tap counts, so this bounds how fast one player can move a count. */
    val homePicks: RequestBudget,
    /**
     * `POST /v1/me/push-tokens` and `POST /v1/me/push-token-removals` together. A device registers its
     * token when it starts, when its session changes and when Firebase gives it a new one.
     */
    val pushTokens: RequestBudget,
    /** `GET /v1/shop`. The shop is read each time it is shown. */
    val shop: RequestBudget,
    /**
     * `POST /v1/me/purchases`. A player buys a few themes at most, and each takes points many answers
     * earned; a resend or a double tap is refused as owned already.
     */
    val purchases: RequestBudget,
    /** Every admin route together, per address, whatever token the request carries. */
    val admin: RequestBudget,
    /**
     * Admin requests whose token is missing or wrong, per address, on top of [admin]: what bounds
     * guessing the admin token. A request with the right one spends none of it, but while it is spent
     * the address is locked out and that one is refused too, or its status would tell a right guess from
     * a wrong one.
     */
    val adminTokenFailures: RequestBudget,
) {
    companion object {
        /**
         * Generous for a person, however fast they tap: 50 answers in a row, with a feed request per
         * batch, fit inside a minute's votes twice over.
         */
        val DEFAULT: RateLimits =
            RateLimits(
                guests = RequestBudget(requests = 60, per = 1.hours),
                refreshes = RequestBudget(requests = 30, per = 1.minutes),
                logins = RequestBudget(requests = 20, per = 1.minutes),
                playGames = RequestBudget(requests = 20, per = 1.minutes),
                registrations = RequestBudget(requests = 20, per = 1.hours),
                logouts = RequestBudget(requests = 30, per = 1.minutes),
                feed = RequestBudget(requests = 120, per = 1.minutes),
                votes = RequestBudget(requests = 120, per = 1.minutes),
                skips = RequestBudget(requests = 120, per = 1.minutes),
                reactions = RequestBudget(requests = 60, per = 1.minutes),
                submissions = RequestBudget(requests = 30, per = 1.hours),
                reports = RequestBudget(requests = 30, per = 1.hours),
                hides = RequestBudget(requests = 60, per = 1.hours),
                deletions = RequestBudget(requests = 10, per = 1.hours),
                stats = RequestBudget(requests = 120, per = 1.minutes),
                mySubmissions = RequestBudget(requests = 120, per = 1.minutes),
                categories = RequestBudget(requests = 120, per = 1.minutes),
                homePickCounts = RequestBudget(requests = 120, per = 1.minutes),
                homePicks = RequestBudget(requests = 30, per = 1.minutes),
                pushTokens = RequestBudget(requests = 60, per = 1.hours),
                shop = RequestBudget(requests = 120, per = 1.minutes),
                purchases = RequestBudget(requests = 30, per = 1.hours),
                admin = RequestBudget(requests = 60, per = 1.minutes),
                adminTokenFailures = RequestBudget(requests = 10, per = 1.minutes),
            )

        /**
         * [DEFAULT], with the count of each budget whose variable is set replaced. A value that is not
         * a whole number of at least 1 fails at config load with the variable named, rather than
         * falling back to the default unnoticed. Blank counts as unset.
         */
        fun fromEnvironment(env: (String) -> String?): RateLimits {
            fun budget(
                variable: String,
                default: RequestBudget,
            ): RequestBudget {
                val raw = env(variable)?.trim()?.takeIf { it.isNotEmpty() } ?: return default
                val requests = raw.toIntOrNull()
                require(requests != null && requests >= 1) {
                    "$variable is \"$raw\"; expected a whole number of requests of at least 1."
                }
                return default.copy(requests = requests)
            }

            return with(DEFAULT) {
                RateLimits(
                    guests = budget("RATE_LIMIT_GUESTS_PER_HOUR", guests),
                    refreshes = budget("RATE_LIMIT_REFRESHES_PER_MINUTE", refreshes),
                    logins = budget("RATE_LIMIT_LOGINS_PER_MINUTE", logins),
                    playGames = budget("RATE_LIMIT_PLAY_GAMES_PER_MINUTE", playGames),
                    registrations = budget("RATE_LIMIT_REGISTRATIONS_PER_HOUR", registrations),
                    logouts = budget("RATE_LIMIT_LOGOUTS_PER_MINUTE", logouts),
                    feed = budget("RATE_LIMIT_FEED_PER_MINUTE", feed),
                    votes = budget("RATE_LIMIT_VOTES_PER_MINUTE", votes),
                    skips = budget("RATE_LIMIT_SKIPS_PER_MINUTE", skips),
                    reactions = budget("RATE_LIMIT_REACTIONS_PER_MINUTE", reactions),
                    submissions = budget("RATE_LIMIT_SUBMISSIONS_PER_HOUR", submissions),
                    reports = budget("RATE_LIMIT_REPORTS_PER_HOUR", reports),
                    hides = budget("RATE_LIMIT_HIDES_PER_HOUR", hides),
                    deletions = budget("RATE_LIMIT_DELETIONS_PER_HOUR", deletions),
                    stats = budget("RATE_LIMIT_STATS_PER_MINUTE", stats),
                    mySubmissions = budget("RATE_LIMIT_MY_SUBMISSIONS_PER_MINUTE", mySubmissions),
                    categories = budget("RATE_LIMIT_CATEGORIES_PER_MINUTE", categories),
                    homePickCounts = budget("RATE_LIMIT_HOME_PICK_COUNTS_PER_MINUTE", homePickCounts),
                    homePicks = budget("RATE_LIMIT_HOME_PICKS_PER_MINUTE", homePicks),
                    pushTokens = budget("RATE_LIMIT_PUSH_TOKENS_PER_HOUR", pushTokens),
                    shop = budget("RATE_LIMIT_SHOP_PER_MINUTE", shop),
                    purchases = budget("RATE_LIMIT_PURCHASES_PER_HOUR", purchases),
                    admin = budget("RATE_LIMIT_ADMIN_PER_MINUTE", admin),
                    adminTokenFailures = budget("RATE_LIMIT_ADMIN_TOKEN_FAILURES_PER_MINUTE", adminTokenFailures),
                )
            }
        }
    }
}

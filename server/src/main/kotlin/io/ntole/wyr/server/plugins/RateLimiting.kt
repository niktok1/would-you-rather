package io.ntole.wyr.server.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.RateLimiter
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.util.date.getTimeMillis
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.auth.verifiedPlayerId
import io.ntole.wyr.server.config.RateLimits
import io.ntole.wyr.server.config.RequestBudget
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.moderation.AdminToken
import io.ntole.wyr.server.moderation.admits
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Each group of routes with a budget of its own in [RateLimits] (CLAUDE.md §8b, *Rate limiting*). A
 * route joins its group through [rateLimit]. `/health` is in none, so Render's checks are never refused.
 */
enum class RouteLimit(
    internal val budgetIn: (RateLimits) -> RequestBudget,
    internal val keyedBy: KeyedBy,
) {
    GUESTS(RateLimits::guests, KeyedBy.ADDRESS),
    REFRESHES(RateLimits::refreshes, KeyedBy.ADDRESS),
    LOGINS(RateLimits::logins, KeyedBy.ADDRESS),
    PLAY_GAMES(RateLimits::playGames, KeyedBy.ADDRESS),
    REGISTRATIONS(RateLimits::registrations, KeyedBy.PLAYER),
    LOGOUTS(RateLimits::logouts, KeyedBy.PLAYER),
    FEED(RateLimits::feed, KeyedBy.PLAYER),
    VOTES(RateLimits::votes, KeyedBy.PLAYER),
    SKIPS(RateLimits::skips, KeyedBy.PLAYER),
    REACTIONS(RateLimits::reactions, KeyedBy.PLAYER),
    SUBMISSIONS(RateLimits::submissions, KeyedBy.PLAYER),
    REPORTS(RateLimits::reports, KeyedBy.PLAYER),
    HIDES(RateLimits::hides, KeyedBy.PLAYER),
    DELETIONS(RateLimits::deletions, KeyedBy.PLAYER),
    STATS(RateLimits::stats, KeyedBy.PLAYER),
    MY_SUBMISSIONS(RateLimits::mySubmissions, KeyedBy.PLAYER),
    CATEGORIES(RateLimits::categories, KeyedBy.ADDRESS),
    HOME_PICK_COUNTS(RateLimits::homePickCounts, KeyedBy.ADDRESS),
    HOME_PICKS(RateLimits::homePicks, KeyedBy.PLAYER),
    PUSH_TOKENS(RateLimits::pushTokens, KeyedBy.PLAYER),
    ADMIN(RateLimits::admin, KeyedBy.ADDRESS),

    /**
     * Spent only by a request [installRateLimits] finds without the right admin token, but once spent it
     * refuses every admin request from the address, the right token's included ([LockingOut]).
     */
    ADMIN_TOKEN_FAILURES(RateLimits::adminTokenFailures, KeyedBy.ADDRESS),
    ;

    internal val id: String = name.lowercase()
    internal val limitName: RateLimitName = RateLimitName(id)
}

/** Whose budget a request to a [RouteLimit] group spends. */
internal enum class KeyedBy {
    /**
     * The player the bearer token names, so players behind one address do not share a budget. A request
     * with no valid token spends its address's budget of the group instead, and authentication then
     * refuses it, so a flood of those is bounded too.
     */
    PLAYER,

    /**
     * The client's address, for a caller with no session to name: one minting a guest, refreshing,
     * logging in, signing in with Play Games, or moderating, or with none needed: one reading the categories or the home picks.
     */
    ADDRESS,
}

private sealed interface LimitKey {
    data class Player(
        val id: String,
    ) : LimitKey

    data class Address(
        val address: String,
    ) : LimitKey
}

/**
 * Installs Ktor's rate limiter, with a budget per [RouteLimit] group from [ServerConfig.rateLimits],
 * and client addresses read from the header [ServerConfig.clientIpHeader] names ([clientAddress]).
 *
 * It counts in memory, per server instance: a second instance would give every client each budget
 * again, so running two needs a shared store first. A key's count is dropped once its period is over,
 * so memory grows only with the clients active in one period.
 *
 * It runs before anything else of the route, authentication and the handler included, so a refused
 * request reads nothing, writes nothing and spends no refresh token. That is also why a per-player key
 * verifies the token itself ([verifiedPlayerId]): no principal is there yet. A refusal is 429 with
 * `Retry-After` in whole seconds, which `StatusPages` renders as `RATE_LIMITED`, and one INFO line.
 */
fun Application.installRateLimits(
    config: ServerConfig,
    tokens: TokenService,
    adminToken: AdminToken?,
) {
    val keys = LimitKeys(tokens, config.clientIpHeader)
    install(RateLimit) {
        RouteLimit.entries.forEach { limit ->
            register(limit.limitName) {
                val budget = limit.budgetIn(config.rateLimits)
                requestKey { call -> keys.of(call, limit.keyedBy) }
                if (limit == RouteLimit.ADMIN_TOKEN_FAILURES) {
                    // Checks the token as requireAdmin will, so only a request it will refuse spends from
                    // this. Once it is spent, though, every request from the address is refused, the right
                    // token's too: were that one let in, 429 would mean wrong and 200 right, and a guesser
                    // past the budget would still learn the token at whatever speed they could send.
                    rateLimiter { _, _ -> LockingOut(RateLimiter.default(budget.requests, budget.per)) }
                    requestWeight { call, _ -> if (adminToken != null && adminToken.admits(call)) 0 else 1 }
                } else {
                    rateLimiter(limit = budget.requests, refillPeriod = budget.per)
                }
                // In place of Ktor's default, which also sends X-RateLimit-* headers: on a route in two
                // groups they would describe whichever was asked first.
                modifyResponse { call, state ->
                    if (state is RateLimiter.State.Exhausted) call.refuse(limit, state.toWait, keys)
                }
            }
        }
    }
}

/**
 * Puts the routes [build] declares in [limit]'s group: every request to them spends from its budget
 * first. Nested, a route is in both groups, the outer asked first.
 */
fun Route.rateLimit(
    limit: RouteLimit,
    build: Route.() -> Unit,
): Route = rateLimit(limit.limitName, build)

/**
 * [budget], but a request that weighs nothing is refused once it is spent. Ktor's own limiter lets
 * one through whatever is left, as it spends nothing, and that would let the moderator's token past a
 * lockout ([RouteLimit.ADMIN_TOKEN_FAILURES]). What it refuses spends nothing either.
 */
private class LockingOut(
    private val budget: RateLimiter,
) : RateLimiter {
    override suspend fun tryConsume(tokens: Int): RateLimiter.State {
        val state = budget.tryConsume(tokens)
        if (tokens > 0 || state !is RateLimiter.State.Available || state.remainingTokens > 0) return state
        // Ktor's limiter tells the time the same way, so the wait is what it would have said.
        return RateLimiter.State.Exhausted((state.refillAtTimeMillis - getTimeMillis()).coerceAtLeast(0).milliseconds)
    }
}

/** Picks the [LimitKey] a request spends, as its group is [KeyedBy]. */
private class LimitKeys(
    private val tokens: TokenService,
    private val clientIpHeader: String?,
) {
    fun of(
        call: ApplicationCall,
        keyedBy: KeyedBy,
    ): LimitKey {
        if (keyedBy == KeyedBy.PLAYER) tokens.verifiedPlayerId(call.request)?.let { return LimitKey.Player(it) }
        return LimitKey.Address(call.request.clientAddress(clientIpHeader))
    }
}

private fun ApplicationCall.refuse(
    limit: RouteLimit,
    wait: Duration,
    keys: LimitKeys,
) {
    val seconds = wait.retryAfterSeconds()
    response.header(HttpHeaders.RetryAfter, seconds)

    // Once per refused request, since the limiter stops at the first group that refuses. Never a
    // header's value: not the token, and not the address, which can come from one.
    val whose =
        when (val key = keys.of(this, limit.keyedBy)) {
            is LimitKey.Player -> "player ${key.id}"
            is LimitKey.Address -> "a client address"
        }
    application.log.info(
        "rate limit ${limit.id} reached for $whose: ${request.httpMethod.value} ${request.path()}, " +
            "retry in $seconds s",
    )
}

/** Rounded up, and never 0, which would tell a client to retry at once into the same refusal. */
internal fun Duration.retryAfterSeconds(): Long = ((inWholeMilliseconds + 999) / 1_000).coerceAtLeast(1)

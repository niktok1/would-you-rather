package io.ntole.wyr.server

import io.ntole.wyr.server.config.RateLimits
import io.ntole.wyr.server.config.RequestBudget
import kotlin.time.Duration.Companion.minutes

/** [budget] for every group of routes. */
internal fun rateLimitsOf(budget: RequestBudget): RateLimits =
    RateLimits(
        guests = budget,
        refreshes = budget,
        registrations = budget,
        logouts = budget,
        feed = budget,
        votes = budget,
        skips = budget,
        likes = budget,
        submissions = budget,
        stats = budget,
        mySubmissions = budget,
        admin = budget,
        adminTokenFailures = budget,
    )

/**
 * Budgets no test's traffic comes near, for a server under test for something else: a flow that ran
 * into a limit would fail for a reason it is not about. `RateLimitTest` pins the limits themselves.
 */
internal val NO_PRACTICAL_LIMIT: RateLimits = rateLimitsOf(RequestBudget(requests = 1_000_000, per = 1.minutes))

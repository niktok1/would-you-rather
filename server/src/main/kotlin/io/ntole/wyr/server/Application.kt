package io.ntole.wyr.server

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.server.auth.GooglePlayGames
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.auth.authRoutes
import io.ntole.wyr.server.auth.playGamesRoutes
import io.ntole.wyr.server.category.categoryRoutes
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.db.DatabaseFactory
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.google.googleHttpClient
import io.ntole.wyr.server.home.homePickRoutes
import io.ntole.wyr.server.moderation.AdminToken
import io.ntole.wyr.server.moderation.moderationRoutes
import io.ntole.wyr.server.player.playerRoutes
import io.ntole.wyr.server.plugins.installPlugins
import io.ntole.wyr.server.plugins.installRateLimits
import io.ntole.wyr.server.push.DecisionNotifier
import io.ntole.wyr.server.push.FcmSender
import io.ntole.wyr.server.push.pushRoutes
import io.ntole.wyr.server.question.questionRoutes
import io.ntole.wyr.server.question.submissionRoutes
import io.ntole.wyr.server.reaction.reactionRoutes
import io.ntole.wyr.server.vote.voteRoutes
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

fun main() {
    val config = ServerConfig.fromEnvironment()

    embeddedServer(
        factory = Netty,
        port = config.port,
        host = "0.0.0.0",
        module = { wyrModule(config) },
    ).start(wait = true)
}

/**
 * Assembles the application.
 *
 * Takes its [config] as a parameter rather than reading the environment itself so tests can
 * stand the whole server up against an isolated in-memory database, the [seeds] a boot writes
 * so they can stand it up on the first seeds alone (`TEST_SEEDS`), and the engine the server calls
 * Google through, [googleEngine], asked for only when a feature that calls Google is on and closed
 * when the server stops, so they can answer for Google with Ktor's MockEngine and never reach it.
 */
fun Application.wyrModule(
    config: ServerConfig,
    seeds: List<Pair<String, Seed.Starter>> = Seed.SEEDS,
    googleEngine: () -> HttpClientEngine = { CIO.create() },
) {
    warnAboutInsecureDefaults(config)

    val database = DatabaseFactory.init(config, monitor, seeds)
    val db = Db(database)
    val tokens = TokenService(config)

    // Built once, for the routes and for the rate limit on failed admin tokens alike.
    val adminToken = config.adminToken?.let { token -> AdminToken(token) }

    installPlugins(config, tokens)
    installRateLimits(config, tokens, adminToken)

    // Work a request starts and does not wait for, a decision's pushes: it outlives the request, never
    // the server.
    val background = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("wyr-background"))
    val engine = if (config.fcmServiceAccount != null || config.playGames != null) googleEngine() else null
    val google = engine?.let(::googleHttpClient)
    monitor.subscribe(ApplicationStopped) {
        background.cancel()
        // A client built over an engine it was handed leaves that engine running when it closes.
        google?.close()
        engine?.close()
    }
    val notifier =
        config.fcmServiceAccount?.let { account ->
            DecisionNotifier(db, FcmSender(account, checkNotNull(google)), background)
        }
    val playGames = config.playGames?.let { client -> GooglePlayGames(client, checkNotNull(google)) }

    routing {
        // Render pings this to decide whether the service is live. In no rate-limit group, so a check
        // is never refused.
        get(WyrApi.Paths.HEALTH) {
            call.respond(mapOf("status" to "ok"))
        }

        authRoutes(db, tokens, config)
        // Not registered at all without Play Games configured, so its path is 404 (CLAUDE.md §8a).
        playGamesRoutes(db, tokens, config, playGames)
        categoryRoutes(db)
        questionRoutes(db)
        submissionRoutes(db)
        voteRoutes(db)
        reactionRoutes(db)
        playerRoutes(db)
        homePickRoutes(db)
        pushRoutes(db)
        // Not registered at all without an admin token, so moderation is off (CLAUDE.md §8d).
        moderationRoutes(db, adminToken, notifier)
    }
}

private fun Application.warnAboutInsecureDefaults(config: ServerConfig) {
    if (config.usesDevJwtSecret) {
        log.warn(
            "JWT_SECRET is unset — using the built-in development secret. Every issued token is " +
                "forgeable by anyone with the source. Set JWT_SECRET before exposing this.",
        )
    }
    if (config.isEphemeralDatabase) {
        log.warn(
            "DATABASE_URL is unset — running on in-memory H2. All players, votes, and points are " +
                "discarded on shutdown.",
        )
    }
    if (config.onRender && config.clientIpHeader == null) {
        log.warn(
            "CLIENT_IP_HEADER is unset on Render, so every request's address is Render's proxy and every " +
                "client shares each per-address rate limit, such as ${config.rateLimits.guests.requests} new " +
                "guests an hour for everyone together. render.yaml sets it to CF-Connecting-IP.",
        )
    }
    if (config.fcmServiceAccount == null) {
        log.info(
            "FCM_SERVICE_ACCOUNT_JSON is unset — push notifications are off. Devices still register their " +
                "push tokens, and no moderator's decision is pushed to its author.",
        )
    }
    if (config.playGames == null) {
        log.info(
            "PLAY_GAMES_CLIENT_ID and PLAY_GAMES_CLIENT_SECRET are unset — Play Games sign-in is off. Its " +
                "route is not served, and players register with a username and password alone.",
        )
    }
    if (config.allowedWebOrigins.isEmpty()) {
        log.info("ALLOWED_WEB_ORIGINS is unset — browser clients will be blocked by CORS.")
    }
    if (config.adminToken == null) {
        log.warn(
            "ADMIN_TOKEN is unset — moderation is off. The admin routes are not served, so no " +
                "submission can be approved or rejected and every one stays pending.",
        )
    } else if (config.usesShortAdminToken) {
        log.warn(
            "ADMIN_TOKEN is shorter than ${ServerConfig.MIN_ADMIN_TOKEN_LENGTH} characters. Whoever " +
                "guesses it can approve and reject every submission, and guesses are limited only per " +
                "address, ${config.rateLimits.adminTokenFailures.requests} a minute, so a caller with many " +
                "addresses gets many more. Use a random one, such as the output of openssl rand -hex 32.",
        )
    }
}

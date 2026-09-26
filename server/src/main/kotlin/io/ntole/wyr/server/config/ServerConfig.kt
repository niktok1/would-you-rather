package io.ntole.wyr.server.config

import io.ntole.wyr.core.api.WyrApi

/**
 * Everything the server reads from the environment.
 *
 * All secrets arrive as Render environment variables and are never committed (CLAUDE.md §8).
 * The defaults exist only so `./gradlew :server:run` works on a laptop with nothing configured.
 */
data class ServerConfig(
    val port: Int,
    val jdbcUrl: String,
    val dbUser: String?,
    val dbPassword: String?,
    val jwtSecret: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    val accessTokenTtlSeconds: Long,
    val refreshTokenTtlSeconds: Long,
    /**
     * The time bound on the grace (CLAUDE.md §8a), from `REFRESH_GRACE_SECONDS`: how long after its
     * rotation a refresh token the rotation displaced still works, once. Null when that is unset, the
     * default, which sets no time bound: the displaced token works until the next rotation displaces it
     * for good, or it expires. 0 turns the grace off, so a token is dead the moment a refresh has spent
     * it. No bound ever lengthens a token's own expiry.
     *
     * The grace is what saves a player whose refresh the server ran but whose answer never arrived,
     * from a dropped connection, the client's own refresh timeout or the app killed mid-refresh: the
     * client still holds the token the server rotated out, and sends it again with its next refresh,
     * which can come days later. Without the grace, or past its bound, that refresh is refused and the
     * client replaces the player with a fresh guest, their points gone. A bound must at least outlast
     * the client's 5-minute refresh timeout (`WyrHttpClient.REFRESH_TIMEOUT`), so that a refresh
     * abandoned there can be sent again.
     *
     * The cost is a copy's. A refresh token copied to a second device, or stolen, still works once after
     * its own player's refresh rotated it out, if it comes before their next one. Once used, the copy
     * keeps working beside the original for as long as the two take turns refreshing, since each
     * refresh leaves the other's token as the previous one, which nothing times out; one drops out when
     * the other refreshes twice in a row, or when it waits as the previous one past a bound. Accepted
     * for guest accounts (CLAUDE.md §8a, *The cost*).
     */
    val refreshGraceSeconds: Long?,
    val allowedWebOrigins: List<WebOrigin>,
    /**
     * The moderator's credential (CLAUDE.md §8d, *Moderation*), from `ADMIN_TOKEN`, or null when that
     * is unset or blank, which turns the admin routes off. There is no default: a built-in token would
     * let anyone with the source moderate, where no token only leaves every submission pending.
     */
    val adminToken: String?,
    /** What one client may send to each group of routes (CLAUDE.md §8b, *Rate limiting*). */
    val rateLimits: RateLimits,
    /**
     * The request header the proxy in front sets to the client's address, overwriting any a client
     * sent, from `CLIENT_IP_HEADER`: a per-address rate limit keys by its value (`clientAddress`).
     * Render's is Cloudflare's `CF-Connecting-IP` (`render.yaml`). Null, the default, trusts no header
     * and takes the socket peer, which is the client itself when nothing stands in between, as on a
     * laptop.
     */
    val clientIpHeader: String?,
    /** True on Render, which sets `RENDER` to `true` for every service. Only a boot warning reads it. */
    val onRender: Boolean,
    /**
     * The oldest build the server serves on each platform, by its `WyrApi.ClientPlatform` name, from
     * `MIN_CLIENT_VERSION_ANDROID`, `_IOS`, `_WEB` and `_DESKTOP` (CLAUDE.md §8b, *Minimum client
     * version*). A platform left out, the default for every one, has no minimum.
     */
    val minClientVersions: Map<String, Int> = emptyMap(),
) {
    /** True when running against the throwaway in-memory database. */
    val isEphemeralDatabase: Boolean get() = jdbcUrl.startsWith("jdbc:h2:")

    val usesDevJwtSecret: Boolean get() = jwtSecret == DEV_JWT_SECRET

    /**
     * True for an admin token short enough to guess. Wrong tokens are rate-limited per address
     * ([RateLimits.adminTokenFailures]), but a caller with many addresses has that budget many times
     * over, so the length is still what stands in the way.
     */
    val usesShortAdminToken: Boolean get() = adminToken != null && adminToken.length < MIN_ADMIN_TOKEN_LENGTH

    companion object {
        const val DEV_JWT_SECRET: String = "dev-only-insecure-secret-do-not-ship"

        /** Shortest admin token the server boots on without a warning. `openssl rand -hex 32` makes 64. */
        const val MIN_ADMIN_TOKEN_LENGTH: Int = 32

        private const val DEFAULT_PORT = 8080
        private const val ACCESS_TTL_SECONDS = 15L * 60L
        private const val REFRESH_TTL_SECONDS = 30L * 24L * 60L * 60L

        private val WEB_SCHEMES = setOf("http", "https")
        private const val MAX_PORT = 65_535
        private const val ANY_HOST = "*"

        fun fromEnvironment(env: (String) -> String? = System::getenv): ServerConfig {
            val databaseUrl = env("DATABASE_URL")?.takeIf { it.isNotBlank() }
            val parsed = databaseUrl?.let(::parseDatabaseUrl)

            return ServerConfig(
                port = env("PORT")?.toIntOrNull() ?: DEFAULT_PORT,
                // No DATABASE_URL means local development: run against H2 so a fresh clone boots.
                jdbcUrl = parsed?.jdbcUrl ?: "jdbc:h2:mem:wyr;DB_CLOSE_DELAY=-1",
                dbUser = parsed?.user,
                dbPassword = parsed?.password,
                jwtSecret = env("JWT_SECRET")?.takeIf { it.isNotBlank() } ?: DEV_JWT_SECRET,
                jwtIssuer = env("JWT_ISSUER")?.takeIf { it.isNotBlank() } ?: "wyr",
                jwtAudience = env("JWT_AUDIENCE")?.takeIf { it.isNotBlank() } ?: "wyr-client",
                accessTokenTtlSeconds =
                    env("ACCESS_TTL_SECONDS")?.toLongOrNull()
                        ?: ACCESS_TTL_SECONDS,
                refreshTokenTtlSeconds =
                    env("REFRESH_TTL_SECONDS")?.toLongOrNull()
                        ?: REFRESH_TTL_SECONDS,
                refreshGraceSeconds = env("REFRESH_GRACE_SECONDS")?.let(::parseRefreshGraceSeconds),
                allowedWebOrigins =
                    env("ALLOWED_WEB_ORIGINS")
                        ?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        ?.map(::parseWebOrigin)
                        .orEmpty(),
                adminToken = env("ADMIN_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }?.let(::parseAdminToken),
                rateLimits = RateLimits.fromEnvironment(env),
                clientIpHeader = env("CLIENT_IP_HEADER")?.let(::parseClientIpHeader),
                onRender = env("RENDER") == "true",
                minClientVersions = parseMinClientVersions(env),
            )
        }

        /**
         * Each platform's minimum build whose variable is set, a whole number of at least 1, trimmed;
         * blank is unset. Anything else fails at config load, naming the variable, rather than leaving
         * the minimum off unnoticed, or refusing every build of the platform.
         */
        internal fun parseMinClientVersions(env: (String) -> String?): Map<String, Int> =
            MIN_CLIENT_VERSION_VARIABLES
                .mapNotNull { (platform, variable) ->
                    val raw = env(variable)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val minimum = raw.toIntOrNull()
                    require(minimum != null && minimum >= 1) {
                        "$variable is \"$raw\"; expected a whole build number of at least 1, or unset for no minimum."
                    }
                    platform to minimum
                }.toMap()

        /** Each platform's variable naming its minimum build. */
        private val MIN_CLIENT_VERSION_VARIABLES: Map<String, String> =
            mapOf(
                WyrApi.ClientPlatform.ANDROID to "MIN_CLIENT_VERSION_ANDROID",
                WyrApi.ClientPlatform.IOS to "MIN_CLIENT_VERSION_IOS",
                WyrApi.ClientPlatform.WEB to "MIN_CLIENT_VERSION_WEB",
                WyrApi.ClientPlatform.DESKTOP to "MIN_CLIENT_VERSION_DESKTOP",
            )

        /**
         * Refuses an admin token no request could present, so moderation cannot be configured on and
         * still be off in effect. A request carries the token in a header, whose value never starts or
         * ends with whitespace (a server trims it) and which a client sends only visible ASCII in. So
         * the token must be visible ASCII, 0x21 to 0x7E, with no whitespace anywhere, which every
         * generated token is. The message never includes the token, which is a secret.
         *
         * Whitespace around it is trimmed first, at [fromEnvironment]: a token pasted into a dashboard
         * often carries the newline its generator printed (`openssl rand -hex 32` does), and one did
         * stop the first Render deploy from booting. Whitespace inside it still fails.
         */
        internal fun parseAdminToken(raw: String): String {
            require(raw.all { it in VISIBLE_ASCII }) {
                "ADMIN_TOKEN holds a character a request header cannot carry; use visible ASCII only, " +
                    "with no whitespace, such as the output of openssl rand -hex 32."
            }
            return raw
        }

        private val VISIBLE_ASCII = '!'..'~'

        /**
         * A whole number of seconds from 0 to a year, trimmed, or null for a blank one, which is unset
         * and so sets no bound. Anything else fails at config load, naming the variable, rather than
         * falling back to the default: 0 is how the grace is turned off, and a mistyped 0 must not leave
         * it on, with no bound at all, unnoticed. A year is far past a refresh token's own lifetime,
         * which bounds the grace anyway, and keeps it in milliseconds clear of overflow.
         */
        internal fun parseRefreshGraceSeconds(raw: String): Long? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val seconds = trimmed.toLongOrNull()
            require(seconds != null && seconds in 0..MAX_REFRESH_GRACE_SECONDS) {
                "REFRESH_GRACE_SECONDS is \"$raw\"; expected a whole number of seconds from 0, which turns the " +
                    "grace off, to $MAX_REFRESH_GRACE_SECONDS, a year, or unset for no bound."
            }
            return seconds
        }

        private const val MAX_REFRESH_GRACE_SECONDS = 365L * 24L * 60L * 60L

        /**
         * A header name, trimmed, or null for a blank one. Anything else fails at config load, naming
         * the variable, and so does a header proxies append to (`X-Forwarded-For`, `Forwarded`): its
         * value is a list that starts with whatever the client wrote, never one address the proxy in
         * front vouches for.
         */
        internal fun parseClientIpHeader(raw: String): String? {
            val name = raw.trim()
            if (name.isEmpty()) return null
            require(name.all { it in HEADER_NAME_CHARS }) {
                "CLIENT_IP_HEADER is \"$raw\"; expected a header name, such as CF-Connecting-IP."
            }
            require(APPENDED_HEADERS.none { it.equals(name, ignoreCase = true) }) {
                "CLIENT_IP_HEADER is $name, which every proxy appends to and a client can write into; " +
                    "name one the proxy in front overwrites, such as CF-Connecting-IP."
            }
            return name
        }

        /** What a header name may hold: RFC 9110's token characters. */
        private val HEADER_NAME_CHARS: Set<Char> =
            (('a'..'z') + ('A'..'Z') + ('0'..'9') + "!#$%&'*+-.^_`|~".toList()).toSet()

        private val APPENDED_HEADERS = listOf("X-Forwarded-For", "Forwarded")

        /**
         * Accepts an origin as a browser sends it (`http://` or `https://` plus `host[:port]`),
         * or a bare `host[:port]`, which allows both schemes. The host may start with one
         * wildcard label (`*.example.com`), and a bare `*` with no scheme allows every origin —
         * the only wildcards Ktor's CORS plugin takes.
         *
         * Anything else fails at config load with the offending entry named, instead of silently
         * blocking the web client or crashing later inside the CORS plugin without saying which
         * entry. A `*` host with a scheme is refused too: Ktor would drop the scheme and allow
         * every origin.
         */
        internal fun parseWebOrigin(raw: String): WebOrigin {
            fun reject(reason: String): Nothing =
                throw IllegalArgumentException(
                    "ALLOWED_WEB_ORIGINS entry \"$raw\" $reason; expected host[:port], " +
                        "http://host[:port], or https://host[:port], where host may start with *.",
                )

            val schemeEnd = raw.indexOf("://")
            val scheme = if (schemeEnd < 0) null else raw.substring(0, schemeEnd).lowercase()
            val host = if (schemeEnd < 0) raw else raw.substring(schemeEnd + "://".length)
            val name = host.substringBefore(':')
            val port = host.substringAfter(':', missingDelimiterValue = "")
            val validPort = port.all(Char::isDigit) && port.toIntOrNull() in 1..MAX_PORT

            if (scheme != null && scheme !in WEB_SCHEMES) reject("has an unsupported scheme")
            if (host.any { it in "/?#" }) reject("has a path, which an origin never does")
            if (name.isEmpty()) reject("has no host")
            if (name.any { it == '@' || it.isWhitespace() }) reject("is not a bare host")
            if (':' in host && !validPort) reject("has a malformed port")

            if (host == ANY_HOST) {
                if (scheme != null) reject("restricts * to a scheme, which Ktor ignores for *")
            } else if ('*' in name) {
                val leadingLabel = name.startsWith("*.") && name.length > 2 && name.count { it == '*' } == 1
                if (!leadingLabel) reject("has a wildcard that is not a single leading *. label")
            }

            return WebOrigin(host = host, scheme = scheme)
        }

        /**
         * Render (like Heroku) exposes Postgres as `postgres://user:pass@host:port/db`, which
         * the JDBC driver will not accept. Translate it rather than making the operator
         * hand-maintain a second copy of the same URL.
         */
        internal fun parseDatabaseUrl(raw: String): ParsedDatabaseUrl {
            if (raw.startsWith("jdbc:")) return ParsedDatabaseUrl(raw, null, null)

            val uri = java.net.URI(raw)
            val userInfo = uri.userInfo?.split(':', limit = 2)
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val query = uri.query?.let { "?$it" }.orEmpty()

            return ParsedDatabaseUrl(
                jdbcUrl = "jdbc:postgresql://${uri.host}$port${uri.path}$query",
                user = userInfo?.getOrNull(0),
                password = userInfo?.getOrNull(1),
            )
        }
    }

    internal data class ParsedDatabaseUrl(
        val jdbcUrl: String,
        val user: String?,
        val password: String?,
    )
}

package io.ntole.wyr.server.config

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
    val allowedWebOrigins: List<WebOrigin>,
) {
    /** True when running against the throwaway in-memory database. */
    val isEphemeralDatabase: Boolean get() = jdbcUrl.startsWith("jdbc:h2:")

    val usesDevJwtSecret: Boolean get() = jwtSecret == DEV_JWT_SECRET

    companion object {
        const val DEV_JWT_SECRET: String = "dev-only-insecure-secret-do-not-ship"

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
                allowedWebOrigins =
                    env("ALLOWED_WEB_ORIGINS")
                        ?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        ?.map(::parseWebOrigin)
                        .orEmpty(),
            )
        }

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

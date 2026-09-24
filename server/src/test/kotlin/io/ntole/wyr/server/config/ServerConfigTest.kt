package io.ntole.wyr.server.config

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ServerConfigTest {
    @Test
    fun `render style postgres url is translated to jdbc with credentials split out`() {
        // Render and Heroku hand out postgres:// URLs, which the JDBC driver refuses outright.
        val parsed =
            ServerConfig.parseDatabaseUrl(
                "postgres://wyr_user:s3cret@dpg-abc123-a.frankfurt-postgres.render.com:5432/wyr_db",
            )

        assertEquals(
            "jdbc:postgresql://dpg-abc123-a.frankfurt-postgres.render.com:5432/wyr_db",
            parsed.jdbcUrl,
        )
        assertEquals("wyr_user", parsed.user)
        assertEquals("s3cret", parsed.password)
    }

    @Test
    fun `an explicit jdbc url is passed through untouched`() {
        val parsed = ServerConfig.parseDatabaseUrl("jdbc:postgresql://localhost:5432/wyr")

        assertEquals("jdbc:postgresql://localhost:5432/wyr", parsed.jdbcUrl)
        assertNull(parsed.user)
        assertNull(parsed.password)
    }

    @Test
    fun `an empty environment yields a runnable but clearly insecure local config`() {
        val config = ServerConfig.fromEnvironment { null }

        assertEquals(8080, config.port)
        assertTrue(config.isEphemeralDatabase)
        assertTrue(config.usesDevJwtSecret)
        assertTrue(config.allowedWebOrigins.isEmpty())
        assertNull(config.adminToken, "moderation is off, rather than on with a token anyone could read")
    }

    @Test
    fun `the admin token comes from ADMIN_TOKEN and a blank one is none`() {
        val token = "0123456789abcdef".repeat(4)

        assertEquals(token, ServerConfig.fromEnvironment(mapOf("ADMIN_TOKEN" to token)::get).adminToken)
        listOf("", "   ").forEach { blank ->
            assertNull(ServerConfig.fromEnvironment(mapOf("ADMIN_TOKEN" to blank)::get).adminToken, "\"$blank\"")
        }
    }

    @Test
    fun `an admin token shorter than the minimum boots but is flagged`() {
        fun configWith(length: Int) = ServerConfig.fromEnvironment(mapOf("ADMIN_TOKEN" to "x".repeat(length))::get)

        assertTrue(configWith(ServerConfig.MIN_ADMIN_TOKEN_LENGTH - 1).usesShortAdminToken)
        assertFalse(configWith(ServerConfig.MIN_ADMIN_TOKEN_LENGTH).usesShortAdminToken)
        assertFalse(ServerConfig.fromEnvironment { null }.usesShortAdminToken, "no token is not a short one")
    }

    @Test
    fun `whitespace around an admin token, such as its generator's newline, is trimmed`() {
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        listOf("$token\n", " $token ", "\t$token\r\n").forEach { pasted ->
            assertEquals(token, ServerConfig.fromEnvironment(mapOf("ADMIN_TOKEN" to pasted)::get).adminToken)
        }
    }

    @Test
    fun `an admin token no request header could carry fails at config load without showing it`() {
        // Configured, it would never match: moderation on in the config and off in effect.
        val unpresentable =
            listOf(
                "inner space-0123456789abcdef0123456789",
                "tab\t0123456789abcdef0123456789abcdef",
                "newline\n0123456789abcdef0123456789abcdef",
                "not-ascii-\u00e9-0123456789abcdef0123456789",
            )

        unpresentable.forEach { raw ->
            val failure =
                assertFailsWith<IllegalArgumentException>("\"$raw\" should be rejected") {
                    ServerConfig.fromEnvironment(mapOf("ADMIN_TOKEN" to raw)::get)
                }
            assertContains(failure.message.orEmpty(), "ADMIN_TOKEN")
            assertFalse(raw.trim() in failure.message.orEmpty(), "the message must not give the secret away")
        }
    }

    @Test
    fun `the rate limits default to the budgets section 8b records`() {
        val limits = ServerConfig.fromEnvironment { null }.rateLimits

        assertEquals(RateLimits.DEFAULT, limits)
        assertEquals(RequestBudget(10, 1.hours), limits.guests)
        assertEquals(RequestBudget(30, 1.minutes), limits.refreshes)
        listOf(limits.feed, limits.votes, limits.skips, limits.stats, limits.mySubmissions).forEach { budget ->
            assertEquals(RequestBudget(120, 1.minutes), budget)
        }
        assertEquals(RequestBudget(60, 1.minutes), limits.likes)
        assertEquals(RequestBudget(30, 1.hours), limits.submissions)
        assertEquals(RequestBudget(60, 1.minutes), limits.admin)
        assertEquals(RequestBudget(10, 1.minutes), limits.adminTokenFailures)
    }

    @Test
    fun `each rate limit variable sets its own budget's count, per the period its name ends in`() {
        val budgets: List<Triple<String, (RateLimits) -> RequestBudget, Duration>> =
            listOf(
                Triple("RATE_LIMIT_GUESTS_PER_HOUR", RateLimits::guests, 1.hours),
                Triple("RATE_LIMIT_REFRESHES_PER_MINUTE", RateLimits::refreshes, 1.minutes),
                Triple("RATE_LIMIT_FEED_PER_MINUTE", RateLimits::feed, 1.minutes),
                Triple("RATE_LIMIT_VOTES_PER_MINUTE", RateLimits::votes, 1.minutes),
                Triple("RATE_LIMIT_SKIPS_PER_MINUTE", RateLimits::skips, 1.minutes),
                Triple("RATE_LIMIT_LIKES_PER_MINUTE", RateLimits::likes, 1.minutes),
                Triple("RATE_LIMIT_SUBMISSIONS_PER_HOUR", RateLimits::submissions, 1.hours),
                Triple("RATE_LIMIT_STATS_PER_MINUTE", RateLimits::stats, 1.minutes),
                Triple("RATE_LIMIT_MY_SUBMISSIONS_PER_MINUTE", RateLimits::mySubmissions, 1.minutes),
                Triple("RATE_LIMIT_ADMIN_PER_MINUTE", RateLimits::admin, 1.minutes),
                Triple("RATE_LIMIT_ADMIN_TOKEN_FAILURES_PER_MINUTE", RateLimits::adminTokenFailures, 1.minutes),
            )

        budgets.forEach { (variable, budgetOf, period) ->
            val limits = ServerConfig.fromEnvironment(mapOf(variable to " 7 ")::get).rateLimits

            assertEquals(RequestBudget(7, period), budgetOf(limits), variable)
            budgets.filter { it.first != variable }.forEach { (other, otherOf, _) ->
                assertEquals(otherOf(RateLimits.DEFAULT), otherOf(limits), "$variable left $other alone")
            }
        }
    }

    @Test
    fun `a rate limit that is not a whole number of at least 1 fails at config load and names its variable`() {
        listOf("0", "-3", "abc", "1.5", "10/min", "2147483648").forEach { raw ->
            val failure =
                assertFailsWith<IllegalArgumentException>("\"$raw\" should be rejected") {
                    ServerConfig.fromEnvironment(mapOf("RATE_LIMIT_VOTES_PER_MINUTE" to raw)::get)
                }
            assertContains(failure.message.orEmpty(), "RATE_LIMIT_VOTES_PER_MINUTE")
        }
        listOf("", "   ").forEach { blank ->
            val limits = ServerConfig.fromEnvironment(mapOf("RATE_LIMIT_VOTES_PER_MINUTE" to blank)::get).rateLimits
            assertEquals(RateLimits.DEFAULT, limits, "blank is unset: \"$blank\"")
        }
    }

    @Test
    fun `no header names the client's address unless CLIENT_IP_HEADER does`() {
        assertNull(ServerConfig.fromEnvironment { null }.clientIpHeader)
        mapOf(
            "CF-Connecting-IP" to "CF-Connecting-IP",
            " True-Client-IP " to "True-Client-IP",
            "" to null,
            "  " to null,
        ).forEach { (raw, header) ->
            assertEquals(
                header,
                ServerConfig.fromEnvironment(mapOf("CLIENT_IP_HEADER" to raw)::get).clientIpHeader,
                raw,
            )
        }
    }

    @Test
    fun `a CLIENT_IP_HEADER that is no header name, or one proxies append to, fails at config load and names it`() {
        listOf("CF Connecting IP", "CF-Connecting-IP:", "CF-Connecting-IP, X-Real-IP", "Réal-IP").forEach { raw ->
            val failure =
                assertFailsWith<IllegalArgumentException>("\"$raw\" should be rejected") {
                    ServerConfig.fromEnvironment(mapOf("CLIENT_IP_HEADER" to raw)::get)
                }
            assertContains(failure.message.orEmpty(), "CLIENT_IP_HEADER")
        }
        // Each proxy appends to these, so their first entry is whatever the client wrote.
        listOf("X-Forwarded-For", "x-forwarded-for", "Forwarded").forEach { raw ->
            val failure =
                assertFailsWith<IllegalArgumentException>("\"$raw\" should be rejected") {
                    ServerConfig.fromEnvironment(mapOf("CLIENT_IP_HEADER" to raw)::get)
                }
            assertContains(failure.message.orEmpty(), "appends to")
        }
    }

    @Test
    fun `Render is recognised by the RENDER variable it sets to true`() {
        assertTrue(ServerConfig.fromEnvironment(mapOf("RENDER" to "true")::get).onRender)
        assertFalse(ServerConfig.fromEnvironment { null }.onRender)
        assertFalse(ServerConfig.fromEnvironment(mapOf("RENDER" to "false")::get).onRender)
    }

    @Test
    fun `environment values win over defaults`() {
        val env =
            mapOf(
                "PORT" to "9999",
                "JWT_SECRET" to "real-secret",
                "DATABASE_URL" to "postgres://u:p@db.example.com/wyr",
                "ALLOWED_WEB_ORIGINS" to "wyr.example.com, localhost:8080",
            )

        val config = ServerConfig.fromEnvironment(env::get)

        assertEquals(9999, config.port)
        assertFalse(config.usesDevJwtSecret)
        assertFalse(config.isEphemeralDatabase)
        assertEquals(
            listOf(WebOrigin("wyr.example.com", scheme = null), WebOrigin("localhost:8080", scheme = null)),
            config.allowedWebOrigins,
        )
    }

    @Test
    fun `a web origin written with its scheme is split into host and scheme`() {
        // The form an operator copies out of a browser; Ktor's allowHost refuses it unsplit.
        val config =
            ServerConfig.fromEnvironment(
                mapOf("ALLOWED_WEB_ORIGINS" to "https://app.example.com, http://localhost:8080")::get,
            )

        assertEquals(
            listOf(WebOrigin("app.example.com", scheme = "https"), WebOrigin("localhost:8080", scheme = "http")),
            config.allowedWebOrigins,
        )
    }

    @Test
    fun `a leading wildcard label and a bare star are the wildcards accepted`() {
        val config =
            ServerConfig.fromEnvironment(
                mapOf("ALLOWED_WEB_ORIGINS" to "*.example.com, https://*.example.com:8443, *")::get,
            )

        assertEquals(
            listOf(
                WebOrigin("*.example.com", scheme = null),
                WebOrigin("*.example.com:8443", scheme = "https"),
                WebOrigin("*", scheme = null),
            ),
            config.allowedWebOrigins,
        )
    }

    @Test
    fun `a web origin that is not a bare origin fails at config load and names the entry`() {
        val malformed =
            listOf(
                "https://app.example.com/play",
                "https://app.example.com/",
                "https://",
                ":8080",
                "ftp://app.example.com",
                "app.example.com:http",
                "app.example.com:99999",
                "user@app.example.com",
                // Ktor drops the scheme for a bare *, so these would open CORS to every origin.
                "https://*",
                "http://*",
                // Wildcards Ktor's CORS plugin refuses, which would otherwise crash the boot
                // later without naming the entry.
                "*:8080",
                "app.*.example.com",
                "*.*.example.com",
                "https://*.",
            )

        malformed.forEach { raw ->
            val failure =
                assertFailsWith<IllegalArgumentException>("\"$raw\" should be rejected") {
                    ServerConfig.fromEnvironment(mapOf("ALLOWED_WEB_ORIGINS" to raw)::get)
                }
            assertContains(failure.message.orEmpty(), "\"$raw\"")
        }
    }
}

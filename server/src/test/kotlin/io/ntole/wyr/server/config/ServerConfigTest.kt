package io.ntole.wyr.server.config

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `an admin token no request header could carry fails at config load without showing it`() {
        // Configured, it would never match: moderation on in the config and off in effect.
        val unpresentable =
            listOf(
                " leading-space-0123456789abcdef0123456789",
                "trailing-space-0123456789abcdef0123456789 ",
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

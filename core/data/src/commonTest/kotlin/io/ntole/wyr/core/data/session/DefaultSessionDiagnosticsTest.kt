package io.ntole.wyr.core.data.session

import io.ntole.wyr.core.data.FlakySecretStorage
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.session.RecoverySecretStatus
import io.ntole.wyr.core.domain.session.SessionInfo
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.RecoverySecretStore
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultSessionDiagnosticsTest {
    @Test
    fun `the expiry is the exp claim in millis`() {
        assertEquals(EXP_SECONDS * 1_000L, jwtExpiryEpochMillis(jwt("""{"sub":"p1","exp":$EXP_SECONDS}""")))
    }

    @Test
    fun `a payload decodes whatever padding its base64 would have needed`() {
        // JWTs drop the padding, so a decoder that insists on it fails two payload lengths in three.
        listOf("", "a", "ab").forEach { filler ->
            val token = jwt("""{"x":"$filler","exp":$EXP_SECONDS}""")
            assertEquals(EXP_SECONDS * 1_000L, jwtExpiryEpochMillis(token), token)
        }
    }

    @Test
    fun `a token that is not a JWT has no expiry`() {
        listOf(
            "",
            "access-a",
            "a.b",
            "a.b.c.d",
            "a.!!!.c",
            "a.${base64Url("not json")}.c",
            "a.${base64Url("[1]")}.c",
        ).forEach { token -> assertNull(jwtExpiryEpochMillis(token), token) }
    }

    @Test
    fun `a JWT without a numeric exp has no expiry`() {
        listOf(
            """{"sub":"p1"}""",
            """{"exp":"$EXP_SECONDS"}""",
            """{"exp":null}""",
            """{"exp":-1}""",
            """{"exp":${Long.MAX_VALUE}}""",
        ).forEach { payload -> assertNull(jwtExpiryEpochMillis(jwt(payload)), payload) }
    }

    @Test
    fun `info reports the stored player and its token's expiry`() =
        runTest {
            val stored = session("a").copy(accessToken = jwt("""{"exp":$EXP_SECONDS}"""))

            assertEquals(SessionInfo("a", EXP_SECONDS * 1_000L), DefaultSessionDiagnostics(storeHolding(stored)).info())
        }

    @Test
    fun `info keeps the player when the token cannot be read`() =
        runTest {
            assertEquals(SessionInfo("a", null), DefaultSessionDiagnostics(storeHolding(session("a"))).info())
        }

    @Test
    fun `info is null with no session stored`() =
        runTest {
            assertNull(DefaultSessionDiagnostics(storeHolding(null)).info())
        }

    @Test
    fun `the recovery secret is reported as kept, none, or unreadable, and never as itself`() =
        runTest {
            val secrets = FlakySecretStorage()
            val recovery = RecoverySecretStore(secrets, InMemoryTokenStorage(), WyrEnvironment.DEV)
            val diagnostics = DefaultSessionDiagnostics(storeHolding(null), recovery)

            assertEquals(RecoverySecretStatus.NONE, diagnostics.recoverySecret())
            recovery.write("secret")
            assertEquals(RecoverySecretStatus.KEPT, diagnostics.recoverySecret())
            secrets.readFails = true
            assertEquals(RecoverySecretStatus.UNREADABLE, diagnostics.recoverySecret())
        }

    @Test
    fun `a platform that keeps no recovery secret says so`() =
        runTest {
            val guestOnly = DefaultSessionDiagnostics(storeHolding(null))

            assertEquals(RecoverySecretStatus.NOT_KEPT_HERE, guestOnly.recoverySecret())
        }

    private companion object {
        const val EXP_SECONDS = 1_790_000_000L

        fun base64Url(text: String): String =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(text.encodeToByteArray())

        fun jwt(payload: String): String = "${base64Url("""{"alg":"HS256","typ":"JWT"}""")}.${base64Url(payload)}.sig"
    }
}

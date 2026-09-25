package io.ntole.wyr.core.data.session

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.FlakySecretStorage
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository.Companion.MAX_FAILED_SECRET_REQUESTS
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.ApiException
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.RecoverySecretStore
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.core.network.trace.HttpTrace
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A guest carried by their recovery secret (CLAUDE.md §8a, *Recovery*): recovered where a device has
 * no session, before any guest is minted in place of a dead one, and given a secret where a session
 * has none. Each [sessions] is a launch of the app over the same storage and the same server.
 */
class RecoverySecretFlowTest {
    private val server = FakeServer()
    private val local = InMemoryTokenStorage()
    private val secrets = FlakySecretStorage()
    private val store = SessionStore(local, WyrEnvironment.LOCAL)
    private val recovery = RecoverySecretStore(secrets, local, WyrEnvironment.LOCAL)
    private val trace = HttpTrace()
    private val client = WyrHttpClient.create(BASE_URL, store, server.engine, trace)

    @Test
    fun `a fresh install that keeps a secret recovers its player and mints no guest`() =
        runTest {
            server.knowPlayer("a", secret = "secret-a")
            recovery.write("secret-a")

            assertEquals("a", sessions().ensure())

            assertEquals(0, server.guestsMinted)
            assertEquals(listOf("secret-a"), server.recoveriesSent)
            assertEquals("a", store.read()?.playerId)
            // Not spent: it recovers again, on the next phone or the next reinstall.
            assertEquals("secret-a", recovery.read())
            assertEquals(0, server.secretRequestsSent)
        }

    @Test
    fun `a secret the server does not know is dropped for the guest minted in its place`() =
        runTest {
            // The dev server restarted, and forgot every player and every secret.
            recovery.write("forgotten")

            assertEquals("guest1", sessions().ensure())

            assertEquals(listOf("forgotten"), server.recoveriesSent)
            assertEquals(1, server.guestsMinted)
            val kept = recovery.read()
            assertEquals("secret-guest1-1", kept)
            assertTrue(server.isLive("secret-guest1-1"))
        }

    /** Left behind, it would hold the place of the secret the guest's session will ask for. */
    @Test
    fun `a secret the server does not know is dropped even when the guest's cannot be kept`() =
        runTest {
            recovery.write("forgotten")
            secrets.writeFails = true

            assertEquals("guest1", sessions().ensure())

            assertNull(recovery.read())
        }

    @Test
    fun `a fresh install with no secret mints a guest and keeps the guest's secret`() =
        runTest {
            assertEquals("guest1", sessions().ensure())

            assertEquals(emptyList(), server.recoveriesSent)
            assertEquals("secret-guest1-1", recovery.read())
            assertEquals("guest1", store.read()?.playerId)
        }

    @Test
    fun `a guest from a server without recovery is stored with no secret`() =
        runTest {
            server.mintsWithoutSecret = true

            assertEquals("guest1", sessions().ensure())

            assertNull(recovery.read())
            assertEquals("guest1", store.read()?.playerId)
        }

    @Test
    fun `a recovery lost on the network mints nothing and the next call recovers`() =
        runTest {
            server.knowPlayer("a", secret = "secret-a")
            recovery.write("secret-a")
            server.recoveriesToLose = 1
            val sessions = sessions()

            val failure = assertFailsWith<WyrException> { sessions.ensure() }

            assertEquals(DomainError.NETWORK, failure.error)
            assertEquals(0, server.guestsMinted)
            assertEquals("secret-a", recovery.read())
            assertEquals("a", sessions.ensure())
            assertEquals(0, server.guestsMinted)
        }

    /** A rollback to a build from before recovery: every path it lacks is a bare 404. */
    @Test
    fun `a recovery refused by a server from before recovery mints nothing`() =
        runTest {
            recovery.write("secret-a")
            server.refuseRecoveriesWith = HttpStatusCode.NotFound to null

            val failure = assertFailsWith<WyrException> { sessions().ensure() }

            assertEquals(DomainError.UNKNOWN, failure.error)
            assertEquals(0, server.guestsMinted)
            assertEquals("secret-a", recovery.read())
        }

    @Test
    fun `a rate-limited recovery mints nothing`() =
        runTest {
            recovery.write("secret-a")
            server.refuseRecoveriesWith = HttpStatusCode.TooManyRequests to ErrorCode.RATE_LIMITED

            val failure = assertFailsWith<WyrException> { sessions().ensure() }

            assertEquals(DomainError.RATE_LIMITED, failure.error)
            assertEquals(0, server.guestsMinted)
        }

    @Test
    fun `a secret store that cannot be read at a fresh install mints a guest rather than fail`() =
        runTest {
            secrets.readFails = true

            assertEquals("guest1", sessions().ensure())

            assertEquals(emptyList(), server.recoveriesSent)
        }

    /**
     * A store that fails a read for a moment, as Play services may while a restored phone starts: the
     * guest's secret would take the place of the only way back to the player's account.
     */
    @Test
    fun `a guest minted while the secret store cannot be read leaves the secret it holds`() =
        runTest {
            server.knowPlayer("a", secret = "secret-a")
            recovery.write("secret-a")
            secrets.readFails = true

            assertEquals("guest1", sessions().ensure())

            secrets.readFails = false
            assertEquals("secret-a", recovery.read())
            // The next launch leaves it as it is, and the next session opened recovers its player.
            sessions().ensure()
            assertEquals(0, server.secretRequestsSent)
            store.clear()
            assertEquals("a", sessions().ensure())
        }

    @Test
    fun `a guest minted while the secret store cannot be read asks for its secret at the next launch`() =
        runTest {
            secrets.readFails = true
            assertEquals("guest1", sessions().ensure())
            secrets.readFails = false

            sessions().ensure()

            assertEquals(1, server.secretRequestsSent)
            assertEquals("secret-guest1-2", recovery.read())
        }

    @Test
    fun `a guest whose secret cannot be kept is still stored`() =
        runTest {
            secrets.writeFails = true

            assertEquals("guest1", sessions().ensure())

            assertEquals("guest1", store.read()?.playerId)
        }

    /** A guest minted before recovery, or one whose secret could not be kept. */
    @Test
    fun `a session with no secret asks for one once and keeps it`() =
        runTest {
            server.knowPlayer("a")
            store.write(session("a"))
            val sessions = sessions()

            assertEquals("a", sessions.ensure())
            assertEquals("a", sessions.ensure())

            assertEquals(1, server.secretRequestsSent)
            val kept = recovery.read()
            assertEquals("secret-a-1", kept)
            // The next launch finds it kept, and asks nothing.
            sessions().ensure()
            assertEquals(1, server.secretRequestsSent)
        }

    /**
     * A server without recovery answers the path it lacks with a bare 404, as production does until it
     * is promoted, and it may have it by the next launch; a proxy's 503 and a 429 pass as well. None of
     * them says the secret could not be kept here.
     */
    @Test
    fun `a refused request for a secret is made again at every launch until the server gives one`() =
        runTest {
            server.knowPlayer("a")
            store.write(session("a"))
            listOf(
                HttpStatusCode.NotFound to null,
                HttpStatusCode.ServiceUnavailable to null,
                HttpStatusCode.TooManyRequests to ErrorCode.RATE_LIMITED,
            ).forEachIndexed { index, refusal ->
                recovery.clear()
                val sentBefore = server.secretRequestsSent
                server.refuseSecretRequestsWith = refusal

                repeat(MAX_FAILED_SECRET_REQUESTS + 2) {
                    val launch = sessions()
                    launch.ensure()
                    launch.ensure()
                }
                server.refuseSecretRequestsWith = null
                sessions().ensure()

                assertEquals(MAX_FAILED_SECRET_REQUESTS + 3, server.secretRequestsSent - sentBefore, "$refusal")
                assertEquals("secret-a-${index + 1}", recovery.read(), "$refusal")
            }
        }

    @Test
    fun `a request for a secret is made once a launch however often the session is asked for`() =
        runTest {
            server.knowPlayer("a")
            store.write(session("a"))
            // Lost, so none of them counts: only the launch holds them back.
            server.secretRequestsToLose = MAX_FAILED_SECRET_REQUESTS + 2
            val launch = sessions()

            repeat(MAX_FAILED_SECRET_REQUESTS + 2) { launch.ensure() }

            assertEquals(1, server.secretRequestsSent)
        }

    @Test
    fun `a secret the store cannot keep counts as a failed request`() =
        runTest {
            server.knowPlayer("a")
            store.write(session("a"))
            secrets.writeFails = true

            repeat(MAX_FAILED_SECRET_REQUESTS + 2) { sessions().ensure() }

            assertEquals(MAX_FAILED_SECRET_REQUESTS, server.secretRequestsSent)
        }

    @Test
    fun `a request for a secret lost on the network does not count and a kept secret clears the count`() =
        runTest {
            server.knowPlayer("a")
            store.write(session("a"))
            secrets.writeFails = true
            sessions().ensure()
            secrets.writeFails = false
            server.secretRequestsToLose = MAX_FAILED_SECRET_REQUESTS

            // Counted, the losses would end the asking before the last launch.
            repeat(MAX_FAILED_SECRET_REQUESTS + 1) { sessions().ensure() }

            assertEquals(MAX_FAILED_SECRET_REQUESTS + 2, server.secretRequestsSent)
            assertEquals("secret-a-2", recovery.read())
            assertEquals(0, recovery.failedRequests("a"))
        }

    @Test
    fun `a new player starts the count again`() =
        runTest {
            server.knowPlayer("a")
            server.knowPlayer("b")
            store.write(session("a"))
            secrets.writeFails = true
            repeat(MAX_FAILED_SECRET_REQUESTS) { sessions().ensure() }

            store.write(session("b"))
            sessions().ensure()

            assertEquals(MAX_FAILED_SECRET_REQUESTS + 1, server.secretRequestsSent)
        }

    /** Asking would kill a live secret the store could not say it held. */
    @Test
    fun `a session asks for nothing while the secret store cannot be read`() =
        runTest {
            server.knowPlayer("a")
            store.write(session("a"))
            secrets.readFails = true

            sessions().ensure()

            assertEquals(0, server.secretRequestsSent)
        }

    /** On iOS the one Keychain item is the account this person's other iPhones share. */
    @Test
    fun `a secret kept for another player is left as it is`() =
        runTest {
            server.knowPlayer("a")
            server.knowPlayer("b", secret = "secret-b")
            store.write(session("a"))
            recovery.write("secret-b")

            sessions().ensure()

            assertEquals(0, server.secretRequestsSent)
            assertEquals("secret-b", recovery.read())
        }

    @Test
    fun `a dead session is replaced by its own player through the secret and no guest is minted`() =
        runTest {
            server.knowPlayer("a", secret = "secret-a")
            store.write(session("a"))
            recovery.write("secret-a")
            val sessions = sessions()
            var attempts = 0

            val result =
                sessions.withSessionRecovery {
                    // The refresh refused, as a rollback to a build that refreshes only the device used
                    // last refuses this one's.
                    if (attempts++ == 0) throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN, status = 401)
                    "ok as ${store.read()?.playerId} with ${store.read()?.refreshToken}"
                }

            assertEquals("ok as a with refresh-a-0", result)
            assertEquals(0, server.guestsMinted)
            assertEquals(listOf("secret-a"), server.recoveriesSent)
        }

    @Test
    fun `concurrent resets of one dead session recover once`() =
        runTest {
            server.knowPlayer("a", secret = "secret-a")
            store.write(session("a"))
            recovery.write("secret-a")
            val sessions = sessions()

            val dead = session("a")
            val players = awaitAll(async { sessions.resetIfStill(dead) }, async { sessions.resetIfStill(dead) })

            assertEquals(listOf("a", "a"), players)
            assertEquals(1, server.recoveriesSent.size)
            assertEquals(0, server.guestsMinted)
        }

    @Test
    fun `a dead session whose secret the server does not know is replaced by one guest`() =
        runTest {
            store.write(session("a"))
            recovery.write("forgotten")
            val sessions = sessions()

            val dead = session("a")
            val players = awaitAll(async { sessions.resetIfStill(dead) }, async { sessions.resetIfStill(dead) })

            assertEquals(listOf("guest1", "guest1"), players)
            assertEquals(1, server.guestsMinted)
            assertEquals("secret-guest1-1", recovery.read())
        }

    @Test
    fun `a dead session whose recovery is lost on the network fails the call and mints nothing`() =
        runTest {
            server.knowPlayer("a", secret = "secret-a")
            store.write(session("a"))
            recovery.write("secret-a")
            server.recoveriesToLose = 1
            val sessions = sessions()

            val failure =
                assertFailsWith<WyrException> {
                    sessions.withSessionRecovery { throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN, status = 401) }
                }

            assertEquals(DomainError.NETWORK, failure.error)
            assertEquals(0, server.guestsMinted)
            // The dead session is gone, so the next call recovers rather than send it again.
            assertNull(store.read())
            assertEquals("a", sessions.ensure())
        }

    @Test
    fun `clearing drops the secret too so the next session is a fresh guest`() =
        runTest {
            server.knowPlayer("a", secret = "secret-a")
            store.write(session("a"))
            recovery.write("secret-a")
            val sessions = sessions()

            sessions.clear()

            assertNull(recovery.read())
            assertEquals("guest1", sessions.ensure())
            assertEquals(emptyList(), server.recoveriesSent)
        }

    @Test
    fun `a reinstall keeps the secret so the next session recovers the player and forgets the count`() =
        runTest {
            server.knowPlayer("a", secret = "secret-a")
            store.write(session("a"))
            recovery.write("secret-a")
            recovery.countFailedRequest("a")
            val sessions = sessions()

            sessions.clearKeepingSecret()

            assertNull(store.read())
            assertEquals(0, recovery.failedRequests("a"))
            assertEquals("a", sessions.ensure())
            assertEquals(listOf("secret-a"), server.recoveriesSent)
            assertEquals(0, server.guestsMinted)
        }

    @Test
    fun `a reinstall on a platform that keeps no secret mints a guest`() =
        runTest {
            server.knowPlayer("a")
            store.write(session("a"))
            val guestOnly = DefaultSessionRepository(AuthApi(client), store)

            guestOnly.clearKeepingSecret()

            assertEquals("guest1", guestOnly.ensure())
        }

    @Test
    fun `a secret that cannot be dropped fails the clear and leaves the session`() =
        runTest {
            store.write(session("a"))
            recovery.write("secret-a")
            secrets.clearFails = true

            val failure = assertFailsWith<WyrException> { sessions().clear() }

            assertEquals(DomainError.NETWORK, failure.error)
            assertEquals("a", store.read()?.playerId)
        }

    /** A phone without Play services: New guest must still work there. */
    @Test
    fun `a secret store that can neither clear nor read lets the clear through`() =
        runTest {
            store.write(session("a"))
            secrets.clearFails = true
            secrets.readFails = true
            val sessions = sessions()

            sessions.clear()

            assertNull(store.read())
            assertEquals("guest1", sessions.ensure())
        }

    @Test
    fun `a platform that keeps no secret mints as before and asks for none`() =
        runTest {
            server.knowPlayer("a")
            store.write(session("a"))
            val guestOnly = DefaultSessionRepository(AuthApi(client), store)

            guestOnly.ensure()
            guestOnly.clear()
            guestOnly.ensure()

            assertEquals(0, server.secretRequestsSent)
            assertEquals(emptyList(), server.recoveriesSent)
            assertEquals(1, server.guestsMinted)
        }

    @Test
    fun `no secret reaches the HTTP trace`() =
        runTest {
            server.knowPlayer("a")
            store.write(session("a"))
            sessions().ensure()
            store.clear()
            sessions().ensure()
            server.forgetEverything()
            store.clear()
            sessions().ensure()

            // A request for a secret, a recovery with it, and a refused one before a mint: every
            // exchange that carried a secret, each of which the server names secret-<player>-<n>.
            val traced = trace.exchanges.value.joinToString("\n")
            assertEquals(4, trace.exchanges.value.size, traced)
            assertFalse("secret-" in traced, traced)
        }

    private fun sessions() = DefaultSessionRepository(AuthApi(client), store, recovery)
}

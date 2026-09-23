package io.ntole.wyr.core.data.session

import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.ApiException
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each guest minted in place of a dead session orphans the old account, so recovery must mint
 * exactly one however many calls fail on that session.
 */
class SessionRecoveryTest {
    private val server = FakeServer()
    private val store: SessionStore = storeHolding(session("a"))
    private val sessions =
        DefaultSessionRepository(AuthApi(WyrHttpClient.create(BASE_URL, store, server.engine)), store)

    @Test
    fun `concurrent resets of one dead session mint one guest`() =
        runTest {
            val players = awaitAll(async { sessions.resetIfStill("a") }, async { sessions.resetIfStill("a") })

            assertEquals(1, server.guestsMinted)
            assertEquals(listOf("guest1", "guest1"), players)
        }

    @Test
    fun `a reset for a session that was already replaced mints nothing`() =
        runTest {
            store.write(session("b"))

            assertEquals("b", sessions.resetIfStill("a"))
            assertEquals(0, server.guestsMinted)
        }

    @Test
    fun `a reset with no session stored mints one`() =
        runTest {
            store.clear()

            assertEquals("guest1", sessions.resetIfStill("a"))
            assertEquals(1, server.guestsMinted)
        }

    @Test
    fun `a failure that comes back after recovery already ran does not recover again`() =
        runTest {
            // Two calls go out as "a". The first fails, recovers and finishes before the second's
            // failure comes back — which must then find "a" already replaced, not replace guest1.
            val secondSent = CompletableDeferred<Unit>()
            val firstDone = CompletableDeferred<Unit>()

            fun callAsStored(onDeadSession: suspend () -> Unit): suspend () -> String =
                {
                    val sentAs = store.read()?.playerId
                    if (sentAs == "a") {
                        onDeadSession()
                        throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN, status = 401)
                    }
                    "ok as $sentAs"
                }

            val first = async { sessions.withSessionRecovery(callAsStored { secondSent.await() }) }
            val second =
                async {
                    sessions.withSessionRecovery(
                        callAsStored {
                            secondSent.complete(Unit)
                            firstDone.await()
                        },
                    )
                }

            assertEquals("ok as guest1", first.await())
            firstDone.complete(Unit)
            assertEquals("ok as guest1", second.await())
            assertEquals(1, server.guestsMinted)
        }
}

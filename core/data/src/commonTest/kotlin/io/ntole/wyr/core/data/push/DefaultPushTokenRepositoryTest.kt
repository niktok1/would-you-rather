package io.ntole.wyr.core.data.push

import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.push.PushPlatform
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.PushApi
import io.ntole.wyr.core.push.PushTokenRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.ntole.wyr.core.push.PushPlatform as WirePushPlatform

/** The push token registered through the real client, against [FakeServer] (CLAUDE.md §8a). */
class DefaultPushTokenRepositoryTest {
    private val server = FakeServer()

    @Test
    fun `a token is registered with the session's bearer for its platform`() =
        runTest {
            val store = storeHolding(null)
            val client = WyrHttpClient.create(BASE_URL, store, server.engine)
            DefaultSessionRepository(AuthApi(client), store).ensure()

            DefaultPushTokenRepository(PushApi(client)).register("token-1", PushPlatform.ANDROID)

            assertEquals<List<Pair<String?, PushTokenRequest>>>(
                listOf("Bearer access-guest1" to PushTokenRequest("token-1", WirePushPlatform.ANDROID)),
                server.pushTokensSentAs,
            )
        }

    /** A registration follows the session and never makes one: a dead session's is only refused. */
    @Test
    fun `a dead session's registration is refused and mints nobody`() =
        runTest {
            val client = WyrHttpClient.create(BASE_URL, storeHolding(session("dead")), server.engine)

            val refused =
                assertFailsWith<WyrException> {
                    DefaultPushTokenRepository(PushApi(client)).register("token-1", PushPlatform.ANDROID)
                }

            assertEquals(DomainError.UNAUTHORIZED, refused.error)
            assertEquals(0, server.guestsMinted)
        }

    @Test
    fun `every platform is named on the wire as it is`() {
        assertEquals(WirePushPlatform.ANDROID, PushPlatform.ANDROID.toWire())
        assertEquals(WirePushPlatform.IOS, PushPlatform.IOS.toWire())
        assertEquals(WirePushPlatform.WEB, PushPlatform.WEB.toWire())
    }
}

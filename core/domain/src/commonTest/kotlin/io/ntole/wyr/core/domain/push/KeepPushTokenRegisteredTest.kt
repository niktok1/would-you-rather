package io.ntole.wyr.core.domain.push

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.session.CurrentSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The push token registered for every session the device stores, and every new token (CLAUDE.md §8a). */
class KeepPushTokenRegisteredTest {
    private val registered = mutableListOf<String>()
    private val push = FakePush()
    private val tokens = FakeTokens()
    private val session = FakeSession()

    @Test
    fun `the session stored at launch is registered for`() =
        runTest {
            session.player.value = "p1"

            running()

            assertEquals(listOf("p1 token-1 ANDROID"), registered)
        }

    /** A mint, a login, a logout's fresh guest, a Play Games sign-in: each a session of its own. */
    @Test
    fun `every session stored after is registered for`() =
        runTest {
            running()
            assertEquals(emptyList(), registered, "no session, nobody to register for")

            session.player.value = "guest1"
            testScheduler.runCurrent()
            session.player.value = "bob"
            testScheduler.runCurrent()

            assertEquals(listOf("guest1 token-1 ANDROID", "bob token-1 ANDROID"), registered)
        }

    @Test
    fun `a new token is registered for the session stored`() =
        runTest {
            session.player.value = "p1"
            running()

            push.token = "token-2"
            push.newTokens.emit("token-2")
            testScheduler.runCurrent()

            assertEquals(listOf("p1 token-1 ANDROID", "p1 token-2 ANDROID"), registered)
        }

    @Test
    fun `a new token with no session stored waits for the next session`() =
        runTest {
            running()

            push.newTokens.emit("token-1")
            testScheduler.runCurrent()
            assertEquals(emptyList(), registered)

            session.player.value = "guest1"
            testScheduler.runCurrent()
            assertEquals(listOf("guest1 token-1 ANDROID"), registered)
        }

    @Test
    fun `a failed registration is dropped and the next session registers again`() =
        runTest {
            tokens.refuse = true
            session.player.value = "p1"
            running()
            assertEquals(listOf("p1 token-1 ANDROID"), registered)

            tokens.refuse = false
            session.player.value = "p2"
            testScheduler.runCurrent()

            assertEquals(listOf("p1 token-1 ANDROID", "p2 token-1 ANDROID"), registered)
        }

    @Test
    fun `no token yet registers nothing`() =
        runTest {
            push.token = null
            session.player.value = "p1"

            running()

            assertEquals(emptyList(), registered)
        }

    @Test
    fun `a build without pushes registers nothing and returns`() =
        runTest {
            session.player.value = "p1"

            KeepPushTokenRegistered(DevicePush.None, tokens, session).run()

            assertEquals(emptyList(), registered)
        }

    private fun TestScope.running() {
        backgroundScope.launch { KeepPushTokenRegistered(push, tokens, session).run() }
        testScheduler.runCurrent()
    }

    private class FakePush : DevicePush {
        override val available = true
        override val platform = PushPlatform.ANDROID
        var token: String? = "token-1"

        override suspend fun token(): String? = token

        override val newTokens = MutableSharedFlow<String>()
        override val received: Flow<Unit> = emptyFlow()

        override fun askPermissionOnce() = Unit
    }

    private inner class FakeTokens : PushTokenRepository {
        var refuse = false

        override suspend fun register(
            token: String,
            platform: PushPlatform,
        ) {
            registered += "${session.current()} $token $platform"
            if (refuse) throw WyrException(DomainError.NETWORK)
        }
    }

    private class FakeSession : CurrentSession {
        val player = MutableStateFlow<String?>(null)

        override fun current(): String? = player.value

        override val sessions: Flow<String> = player.filterNotNull()
    }
}

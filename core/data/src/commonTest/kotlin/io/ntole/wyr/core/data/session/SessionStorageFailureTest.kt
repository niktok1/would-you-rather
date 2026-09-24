package io.ntole.wyr.core.data.session

import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.ApiException
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A store that cannot make a change durable: a full disk, where Android's `commit()` returns false
 * and `AndroidTokenStorage` throws. The ViewModels catch only `WyrException`, so anything else
 * leaving the data layer crashes the app.
 */
class SessionStorageFailureTest {
    private val server = FakeServer()
    private val storage = UndurableStorage()
    private val store = SessionStore(storage, WyrEnvironment.LOCAL)
    private val sessions =
        DefaultSessionRepository(AuthApi(WyrHttpClient.create(BASE_URL, store, server.engine)), store)

    @Test
    fun `a guest the store cannot save fails as NETWORK and is not minted again`() =
        runTest {
            storage.failing = true

            val failure = assertFailsWith<WyrException> { sessions.ensure() }

            assertEquals(DomainError.NETWORK, failure.error)
            // Still in memory, as Android keeps it: the next call carries on as that guest.
            assertEquals("guest1", sessions.ensure())
            assertEquals(1, server.guestsMinted)
        }

    @Test
    fun `a session the store cannot clear fails as NETWORK`() =
        runTest {
            store.write(session("a"))
            storage.failing = true

            val failure = assertFailsWith<WyrException> { sessions.clear() }

            assertEquals(DomainError.NETWORK, failure.error)
            assertNull(sessions.currentPlayerId())
        }

    @Test
    fun `a dead session the store cannot clear fails recovery as NETWORK`() =
        runTest {
            store.write(session("a"))
            storage.failing = true

            val failure =
                assertFailsWith<WyrException> {
                    sessions.withSessionRecovery { throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN, status = 401) }
                }

            assertEquals(DomainError.NETWORK, failure.error)
            // The dead session is gone from memory, so the next call mints the one guest for it.
            storage.failing = false
            assertEquals("guest1", sessions.ensure())
            assertEquals(1, server.guestsMinted)
        }
}

/** Makes every change in memory and, while [failing], reports it as never reaching the disk. */
private class UndurableStorage : TokenStorage {
    private val values = mutableMapOf<String, String>()
    var failing = false

    override fun read(key: String): String? = values[key]

    override suspend fun write(
        key: String,
        value: String,
    ) {
        values[key] = value
        failIfFailing()
    }

    override suspend fun remove(key: String) {
        values.remove(key)
        failIfFailing()
    }

    private fun failIfFailing() {
        if (failing) throw IOException("the change never reached the disk")
    }
}

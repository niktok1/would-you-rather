package io.ntole.wyr.admin.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ntole.wyr.admin.moderation.ModerationViewModel
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.GetQuestions
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.moderation.RestoreQuestion
import io.ntole.wyr.core.domain.moderation.RetireQuestion
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.network.RecoverySecretStorage
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.Koin
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.mp.KoinPlatform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's own wiring, with nothing stood in for: it has no platform module, so what resolves here
 * is what the desktop window and the page get.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminModuleTest {
    @BeforeTest
    fun setUp() {
        // The ViewModel's scope runs on Dispatchers.Main.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        // For the tests that start the app's own Koin; stopping one that never started does nothing.
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun `the app resolves from its own modules for every environment`() {
        WyrEnvironment.entries.forEach { environment ->
            val koin = koinFor(environment)

            koin.get<ModerationViewModel>()
            assertEquals(environment, koin.get<WyrEnvironment>())
            koin.close()
        }
    }

    @Test
    fun `nothing the app is wired with can make or keep a player session`() =
        runTest {
            WyrEnvironment.entries.forEach { environment ->
                val koin = koinFor(environment)

                assertNull(koin.getOrNull<SessionRepository>(), "nothing can mint a guest")
                assertNull(koin.getOrNull<SessionStore>())
                assertNull(koin.getOrNull<TokenStorage>(), "nothing can store a session")
                assertNull(koin.getOrNull<RecoverySecretStorage>(), "nothing can keep or recover a player")
                assertNull(koin.getOrNull<GetNextQuestion>(), "no player use case at all")

                val sent = requestsOf(koin)

                assertEquals(6, sent.size, "one request per moderation use case")
                sent.forEach { request ->
                    assertTrue(request.url.startsWith(environment.apiBaseUrl + "/v1/admin/"), request.url)
                    assertEquals(TOKEN.value, request.adminToken, request.url)
                    assertNull(request.authorization, "a bearer token went to ${request.url}")
                }
                koin.close()
            }
        }

    @Test
    fun `initAdminKoin starts the environment each entry point's name names`() {
        mapOf(
            "local" to WyrEnvironment.LOCAL,
            " Dev " to WyrEnvironment.DEV,
            "prod" to WyrEnvironment.PROD,
            null to WyrEnvironment.LOCAL,
        ).forEach { (name, environment) ->
            assertEquals(environment, initAdminKoin(environmentName = name), "\"$name\"")
            assertEquals(environment, KoinPlatform.getKoin().get<WyrEnvironment>(), "\"$name\"")
            stopKoin()
        }
    }

    @Test
    fun `an environment name it does not know stops the app before Koin starts`() {
        val failure = assertFailsWith<IllegalArgumentException> { initAdminKoin(environmentName = "staging") }

        assertTrue("\"staging\"" in failure.message.orEmpty(), failure.message)
        assertNull(KoinPlatform.getKoinOrNull())
    }

    private fun koinFor(environment: WyrEnvironment): Koin = koinApplication { modules(adminModules(environment)) }.koin

    /**
     * Every moderation use case the app binds, called once each, with each request stopped at the
     * engine's door and kept: what it would have sent, and where.
     */
    private suspend fun requestsOf(koin: Koin): List<Sent> {
        val sent = mutableListOf<Sent>()
        val client = koin.get<HttpClient>()
        client.plugin(HttpSend).intercept { request ->
            sent +=
                Sent(
                    url = request.url.buildString(),
                    adminToken = request.headers[WyrApi.Headers.ADMIN_TOKEN],
                    authorization = request.headers[HttpHeaders.Authorization],
                )
            throw NotSent()
        }
        val reason = requireNotNull(RejectionReason.of("a duplicate"))

        // Each fails as NETWORK once its request is stopped; what matters is what it sent.
        assertFailsWith<WyrException> { koin.get<GetPendingSubmissions>()(TOKEN) }
        assertFailsWith<WyrException> { koin.get<ApproveSubmission>()(TOKEN, "q1") }
        assertFailsWith<WyrException> { koin.get<RejectSubmission>()(TOKEN, "q1", reason) }
        assertFailsWith<WyrException> { koin.get<GetQuestions>()(TOKEN) }
        assertFailsWith<WyrException> { koin.get<RetireQuestion>()(TOKEN, "q1") }
        assertFailsWith<WyrException> { koin.get<RestoreQuestion>()(TOKEN, "q1") }
        client.close()
        return sent
    }

    private data class Sent(
        val url: String,
        val adminToken: String?,
        val authorization: String?,
    )

    private class NotSent : Exception("stopped before the engine")

    private companion object {
        val TOKEN = requireNotNull(AdminToken.of("admin-token-for-tests-only"))
    }
}

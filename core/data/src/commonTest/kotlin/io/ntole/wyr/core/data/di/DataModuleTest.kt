package io.ntole.wyr.core.data.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.GetQuestions
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.moderation.RestoreQuestion
import io.ntole.wyr.core.domain.moderation.RetireQuestion
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.ModerationApi
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Where the data module's requests go, read off a request as it leaves, after every plugin has had
 * its say and before the engine sends it: the one place a build could still talk to a server nobody
 * chose (CLAUDE.md §8e).
 */
class DataModuleTest {
    @Test
    fun `each environment's requests go to its own server`() =
        runTest {
            WyrEnvironment.entries.forEach { environment ->
                val koin =
                    koinApplication {
                        modules(module { single<TokenStorage> { InMemoryTokenStorage() } }, dataModule(environment))
                    }.koin
                val client = koin.get<HttpClient>()
                client.plugin(HttpSend).intercept { request -> throw NotSent(request.url.buildString()) }

                val request = assertFailsWith<NotSent>(environment.name) { koin.get<AuthApi>().guest() }

                assertEquals(environment.apiBaseUrl + WyrApi.Paths.AUTH_GUEST, request.url, environment.name)
                client.close()
                koin.close()
            }
        }

    @Test
    fun `the game's data module binds nothing of the moderator's`() {
        val koin =
            koinApplication {
                modules(module { single<TokenStorage> { InMemoryTokenStorage() } }, dataModule(WyrEnvironment.LOCAL))
            }.koin

        // Moderating is the moderation app's alone (CLAUDE.md §8d, Moderation).
        assertNull(koin.getOrNull<ModerationApi>())
        assertNull(koin.getOrNull<ModerationRepository>())
        assertNull(koin.getOrNull<GetPendingSubmissions>())
        assertNull(koin.getOrNull<ApproveSubmission>())
        assertNull(koin.getOrNull<RejectSubmission>())
        assertNull(koin.getOrNull<GetQuestions>())
        assertNull(koin.getOrNull<RetireQuestion>())
        assertNull(koin.getOrNull<RestoreQuestion>())
        koin.close()
    }

    @Test
    fun `a client that only moderates needs no storage, binds no session, and sends to its own server`() =
        runTest {
            WyrEnvironment.entries.forEach { environment ->
                // No TokenStorage: the moderation app has no player session to keep.
                val koin = koinApplication { modules(moderationDataModule(environment)) }.koin
                val client = koin.get<HttpClient>()
                client.plugin(HttpSend).intercept { request ->
                    throw NotSent(request.url.buildString(), request.headers[WyrApi.Headers.ADMIN_TOKEN])
                }

                listOf(
                    koin.get<GetPendingSubmissions>(),
                    koin.get<ApproveSubmission>(),
                    koin.get<RejectSubmission>(),
                    koin.get<GetQuestions>(),
                    koin.get<RetireQuestion>(),
                    koin.get<RestoreQuestion>(),
                ).forEach { useCase -> assertNotNull(useCase, environment.name) }
                assertNull(koin.getOrNull<SessionRepository>(), "nothing can mint a guest")
                assertNull(koin.getOrNull<TokenStorage>())

                val request =
                    assertFailsWith<NotSent>(environment.name) { koin.get<ModerationApi>().questions(ADMIN_TOKEN) }

                assertEquals(environment.apiBaseUrl + WyrApi.Paths.ADMIN_QUESTIONS, request.url.substringBefore('?'))
                assertEquals(ADMIN_TOKEN, request.adminToken)
                client.close()
                koin.close()
            }
        }

    /** Stops a request at the engine's door, carrying the URL it was about to go to and its admin token. */
    private class NotSent(
        val url: String,
        val adminToken: String? = null,
    ) : Exception(url)

    private companion object {
        const val ADMIN_TOKEN = "admin-token-for-tests-only"
    }
}

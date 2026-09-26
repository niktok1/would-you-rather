package io.ntole.wyr.core.data.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.domain.account.LogIn
import io.ntole.wyr.core.domain.account.LogOut
import io.ntole.wyr.core.domain.account.RegisterAccount
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.moderation.AddCategory
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.BlockAuthor
import io.ntole.wyr.core.domain.moderation.DismissReports
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.GetQuestions
import io.ntole.wyr.core.domain.moderation.GetReportedQuestions
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.moderation.RenameCategory
import io.ntole.wyr.core.domain.moderation.RestoreQuestion
import io.ntole.wyr.core.domain.moderation.RetireQuestion
import io.ntole.wyr.core.domain.moderation.UnblockAuthor
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.CategoryApi
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
                        modules(
                            module { single<TokenStorage> { InMemoryTokenStorage() } },
                            dataModule(environment, analytics = null),
                        )
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
                modules(
                    module { single<TokenStorage> { InMemoryTokenStorage() } },
                    dataModule(WyrEnvironment.LOCAL, analytics = null),
                )
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
        assertNull(koin.getOrNull<AddCategory>())
        assertNull(koin.getOrNull<RenameCategory>())
        assertNull(koin.getOrNull<GetReportedQuestions>())
        assertNull(koin.getOrNull<DismissReports>())
        assertNull(koin.getOrNull<BlockAuthor>())
        assertNull(koin.getOrNull<UnblockAuthor>())
        koin.close()
    }

    @Test
    fun `the game's data module binds the account's use cases`() {
        val koin =
            koinApplication {
                modules(
                    module { single<TokenStorage> { InMemoryTokenStorage() } },
                    dataModule(WyrEnvironment.LOCAL, analytics = null),
                )
            }.koin

        assertNotNull(koin.get<RegisterAccount>())
        assertNotNull(koin.get<LogIn>())
        assertNotNull(koin.get<LogOut>())
        koin.close()
    }

    /** No key, as in every test and CI build: the switch is there, and nothing is kept or sent (§8g). */
    @Test
    fun `the game's data module binds analytics that without a key keeps nothing`() =
        runTest {
            val storage = InMemoryTokenStorage()
            val koin =
                koinApplication {
                    modules(
                        module { single<TokenStorage> { storage } },
                        dataModule(WyrEnvironment.LOCAL, analytics = null),
                    )
                }.koin

            val analytics = koin.get<Analytics>()
            analytics.track("app_opened")
            analytics.identify("player-1")
            analytics.flush()

            assertEquals(true, analytics.enabled.value)
            assertNull(storage.read("wyr.analytics.id.local"))
            koin.close()
        }

    /** The moderator's app reports nothing (§8g). */
    @Test
    fun `a client that only moderates binds no analytics`() {
        val koin = koinApplication { modules(moderationDataModule(WyrEnvironment.LOCAL)) }.koin

        assertNull(koin.getOrNull<Analytics>())
        koin.close()
    }

    @Test
    fun `the game and a client that only moderates both read the categories from their own server`() =
        runTest {
            WyrEnvironment.entries.forEach { environment ->
                val game =
                    listOf(
                        module { single<TokenStorage> { InMemoryTokenStorage() } },
                        dataModule(environment, analytics = null),
                    )
                listOf(game, listOf(moderationDataModule(environment))).forEach { modules ->
                    val koin = koinApplication { modules(modules) }.koin
                    val client = koin.get<HttpClient>()
                    client.plugin(HttpSend).intercept { request -> throw NotSent(request.url.buildString()) }

                    assertNotNull(koin.get<GetCategories>(), environment.name)
                    val request = assertFailsWith<NotSent>(environment.name) { koin.get<CategoryApi>().all() }

                    assertEquals(environment.apiBaseUrl + WyrApi.Paths.CATEGORIES, request.url, environment.name)
                    client.close()
                    koin.close()
                }
            }
        }

    @Test
    fun `a client that only moderates needs no storage and binds no session and sends to its own server`() =
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
                    koin.get<AddCategory>(),
                    koin.get<RenameCategory>(),
                    koin.get<GetReportedQuestions>(),
                    koin.get<DismissReports>(),
                    koin.get<BlockAuthor>(),
                    koin.get<UnblockAuthor>(),
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

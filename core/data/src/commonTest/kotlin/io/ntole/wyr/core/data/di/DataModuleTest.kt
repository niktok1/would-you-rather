package io.ntole.wyr.core.data.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.RecoverySecretStorage
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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

    /**
     * The first request of a device with no session: a recovery where the platform keeps a secret
     * for this environment, and a mint where it keeps none, or keeps only another environment's
     * (CLAUDE.md §8a, *Recovery*; §8e).
     */
    @Test
    fun `a device recovers with its environment's secret where the platform keeps one, and mints where it does not`() =
        runTest {
            val devSecretOnly =
                object : RecoverySecretStorage {
                    override suspend fun read(key: String): String? = if (key == "wyr.recovery.dev") "secret" else null

                    override suspend fun write(
                        key: String,
                        value: String,
                    ) = Unit

                    override suspend fun clear(key: String) = Unit
                }

            assertEquals(WyrApi.Paths.AUTH_RECOVER, firstRequestPath(WyrEnvironment.DEV, devSecretOnly))
            assertEquals(WyrApi.Paths.AUTH_GUEST, firstRequestPath(WyrEnvironment.PROD, devSecretOnly))
            assertEquals(WyrApi.Paths.AUTH_GUEST, firstRequestPath(WyrEnvironment.DEV, secrets = null))
        }

    /** The path of the first request a fresh device's [SessionRepository.ensure] sends. */
    private suspend fun firstRequestPath(
        environment: WyrEnvironment,
        secrets: RecoverySecretStorage?,
    ): String {
        val platform =
            module {
                single<TokenStorage> { InMemoryTokenStorage() }
                if (secrets != null) single<RecoverySecretStorage> { secrets }
            }
        val koin = koinApplication { modules(platform, dataModule(environment)) }.koin
        val client = koin.get<HttpClient>()
        client.plugin(HttpSend).intercept { request -> throw NotSent(request.url.buildString()) }

        // The data layer reports the request that was never sent as a failure of its own.
        val failure = assertFailsWith<WyrException> { koin.get<SessionRepository>().ensure() }

        client.close()
        koin.close()
        val request = assertIs<NotSent>(failure.cause)
        return request.url.removePrefix(environment.apiBaseUrl)
    }

    /** Stops a request at the engine's door, carrying the URL it was about to go to. */
    private class NotSent(
        val url: String,
    ) : Exception(url)
}

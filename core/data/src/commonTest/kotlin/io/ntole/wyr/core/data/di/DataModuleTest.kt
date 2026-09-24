package io.ntole.wyr.core.data.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    /** Stops a request at the engine's door, carrying the URL it was about to go to. */
    private class NotSent(
        val url: String,
    ) : Exception(url)
}

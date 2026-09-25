package io.ntole.wyr.core.network.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.network.BASE_URL
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.jsonHeaders
import io.ntole.wyr.core.network.session
import io.ntole.wyr.core.network.storeHolding
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthApiTest {
    @Test
    fun `a guest's mint is read as its session whatever else the answer carries`() =
        runTest {
            // A server that still sends a recovery secret beside the session, as d4a9dbf does.
            val minted =
                """{"playerId":"g1","accessToken":"access-g1","refreshToken":"refresh-g1",""" +
                    """"accessTokenExpiresInSeconds":900,"recoverySecret":"secret-g1"}"""
            val engine = MockEngine { respond(minted, HttpStatusCode.OK, jsonHeaders) }

            val session = AuthApi(WyrHttpClient.create(BASE_URL, storeHolding(null), engine)).guest()

            assertEquals(session("g1"), session)
        }
}

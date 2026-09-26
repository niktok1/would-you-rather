package io.ntole.wyr.server.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString

/** The OAuth client the tests' servers exchange codes as: never a real one. */
internal val TEST_PLAY_GAMES_CLIENT =
    PlayGamesClient(clientId = "test-client.apps.googleusercontent.com", clientSecret = "test-client-secret")

/**
 * Google, as a MockEngine, for Play Games sign-ins: a code in [players] is exchanged for an access token,
 * which `players/me` answers with that code's Play Games player; any other code is refused as spent.
 * [token] and [playersMe], when set, answer instead. Nothing reaches Google.
 */
internal class FakePlayGames(
    val players: Map<String, String> = emptyMap(),
    val token: (MockRequestHandleScope.() -> HttpResponseData)? = null,
    val playersMe: (MockRequestHandleScope.() -> HttpResponseData)? = null,
) {
    /** Every exchange's form, by field. */
    val exchanges = mutableListOf<Map<String, String?>>()

    /** Every `players/me` read's Authorization header. */
    val reads = mutableListOf<String?>()

    val engine =
        MockEngine { request ->
            when (request.url.toString()) {
                "https://oauth2.googleapis.com/token" -> {
                    val form = parseQueryString(request.body.toByteArray().decodeToString())
                    exchanges += form.names().associateWith { form[it] }
                    token?.let { return@MockEngine it() }
                    val code = form["code"]
                    if (code in players) {
                        json("""{"access_token":"access-for-$code","expires_in":3599,"token_type":"Bearer"}""")
                    } else {
                        json(
                            """{"error":"invalid_grant","error_description":"Bad Request"}""",
                            HttpStatusCode.BadRequest,
                        )
                    }
                }

                "https://games.googleapis.com/games/v1/players/me" -> {
                    val authorization = request.headers[HttpHeaders.Authorization]
                    reads += authorization
                    playersMe?.let { return@MockEngine it() }
                    val code = authorization?.removePrefix("Bearer access-for-")
                    val player = players[code] ?: return@MockEngine json("{}", HttpStatusCode.Unauthorized)
                    json("""{"kind":"games#player","playerId":"$player","displayName":"Someone"}""")
                }

                else -> {
                    error("no test calls ${request.url}")
                }
            }
        }

    companion object {
        fun MockRequestHandleScope.json(
            body: String,
            status: HttpStatusCode = HttpStatusCode.OK,
        ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

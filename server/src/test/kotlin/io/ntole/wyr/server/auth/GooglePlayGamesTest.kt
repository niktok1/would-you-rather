package io.ntole.wyr.server.auth

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.server.auth.FakePlayGames.Companion.json
import io.ntole.wyr.server.google.googleHttpClient
import io.ntole.wyr.server.printed
import io.ntole.wyr.server.withLogCapture
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Asking Google who a Play Games server auth code names (CLAUDE.md §8a, *Play Games sign-in*), with
 * Google answered by a MockEngine: nothing here reaches Google.
 */
class GooglePlayGamesTest {
    @Test
    fun `a code is exchanged as the game's client and the player read with the access it grants`() =
        runBlocking {
            val google = FakePlayGames(players = mapOf("code-1" to "g-player-1"))

            val answer = google.verifier().playerOf("code-1")

            assertEquals(PlayGamesAnswer.Player("g-player-1"), answer)
            assertEquals(
                mapOf<String, String?>(
                    "grant_type" to "authorization_code",
                    "code" to "code-1",
                    "client_id" to TEST_PLAY_GAMES_CLIENT.clientId,
                    "client_secret" to TEST_PLAY_GAMES_CLIENT.clientSecret,
                    "redirect_uri" to "",
                ),
                google.exchanges.single(),
            )
            assertEquals(listOf<String?>("Bearer access-for-code-1"), google.reads)
        }

    @Test
    fun `a code Google refuses as spent is Refused and nothing is read`() =
        runBlocking {
            val google = FakePlayGames()

            assertEquals(PlayGamesAnswer.Refused, google.verifier().playerOf("spent-code"))
            assertEquals(emptyList<String?>(), google.reads)
        }

    @Test
    fun `a grant that does not reach Play Games is Refused`() =
        runBlocking {
            listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden).forEach { status ->
                val google = FakePlayGames(players = mapOf("code-1" to "g-1"), playersMe = { json("{}", status) })

                assertEquals(PlayGamesAnswer.Refused, google.verifier().playerOf("code-1"), "$status")
            }
        }

    /** The server's own fault or Google's, never the code's: the client is told to try later, not to get a new code. */
    @Test
    fun `anything else Google answers is Unavailable`() =
        runBlocking {
            val cases =
                listOf(
                    FakePlayGames(token = { json("""{"error":"invalid_client"}""", HttpStatusCode.Unauthorized) }),
                    FakePlayGames(token = { json("""{"error":"invalid_request"}""", HttpStatusCode.BadRequest) }),
                    FakePlayGames(token = { json("<html>unavailable</html>", HttpStatusCode.ServiceUnavailable) }),
                    FakePlayGames(token = { json("not json") }),
                    FakePlayGames(token = { throw IOException("connection refused") }),
                    FakePlayGames(
                        players = mapOf("code-1" to "g-1"),
                        playersMe = { json("{}", HttpStatusCode.InternalServerError) },
                    ),
                    FakePlayGames(
                        players = mapOf("code-1" to "g-1"),
                        playersMe = { json("""{"kind":"games#player"}""") },
                    ),
                    FakePlayGames(players = mapOf("code-1" to "g-1"), playersMe = { json("""{"playerId":""}""") }),
                    FakePlayGames(
                        players = mapOf("code-1" to "g-1"),
                        playersMe = { json("""{"playerId":"has space"}""") },
                    ),
                    FakePlayGames(
                        players = mapOf("code-1" to "g-1"),
                        playersMe = { json("""{"playerId":"${"9".repeat(256)}"}""") },
                    ),
                    FakePlayGames(players = mapOf("code-1" to "g-1"), playersMe = { throw IOException("reset") }),
                )

            cases.forEachIndexed { index, google ->
                assertEquals(PlayGamesAnswer.Unavailable, google.verifier().playerOf("code-1"), "case $index")
            }
        }

    @Test
    fun `nothing it logs holds the code the secret or an access token`() =
        withLogCapture { logged ->
            runBlocking {
                val cases =
                    listOf(
                        FakePlayGames(token = {
                            json(
                                """{"error":"invalid_client","error_description":"$CODE"}""",
                                HttpStatusCode.Unauthorized,
                            )
                        }),
                        FakePlayGames(token = { throw IOException("failed for $CODE") }),
                        FakePlayGames(
                            players = mapOf(CODE to "g-1"),
                            playersMe = { json("{}", HttpStatusCode.InternalServerError) },
                        ),
                        FakePlayGames(
                            players = mapOf(CODE to "g-1"),
                            playersMe = { json("{}", HttpStatusCode.Forbidden) },
                        ),
                    )
                cases.forEach { google -> google.verifier().playerOf(CODE) }

                val printed = logged.printed().joinToString("\n")
                listOf(CODE, TEST_PLAY_GAMES_CLIENT.clientSecret, "access-for-").forEach { secret ->
                    assertFalse(printed.contains(secret), "logged a secret: $printed")
                }
            }
        }

    private fun FakePlayGames.verifier() = GooglePlayGames(TEST_PLAY_GAMES_CLIENT, googleHttpClient(engine))

    private companion object {
        const val CODE = "4/0AX4XfWh-a-code-that-must-never-be-logged"
    }
}

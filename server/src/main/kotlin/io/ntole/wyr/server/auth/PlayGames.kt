package io.ntole.wyr.server.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ntole.wyr.server.db.Identities
import io.ntole.wyr.server.google.GoogleJson
import io.ntole.wyr.server.google.oauthErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * The OAuth client of this server's game in Google Cloud (CLAUDE.md §8b, *Play Games sign-in*), from
 * `PLAY_GAMES_CLIENT_ID` and `PLAY_GAMES_CLIENT_SECRET`: what exchanges a Play Games server auth code.
 *
 * [toString] shows none of the secret, so nothing that prints the server's configuration can log it.
 */
class PlayGamesClient(
    val clientId: String,
    val clientSecret: String,
) {
    override fun toString(): String = "PlayGamesClient(clientId=$clientId, clientSecret=***)"
}

/** Who Google says a Play Games server auth code names. */
sealed interface PlayGamesAnswer {
    /** The Play Games player the code names, by the id Google gives them in this game. */
    data class Player(
        val playerId: String,
    ) : PlayGamesAnswer

    /** Google refused the code: spent, expired, or another app's. */
    data object Refused : PlayGamesAnswer

    /** Google could not be asked, or refused this server itself. Already logged. */
    data object Unavailable : PlayGamesAnswer
}

/** Asks Google who a Play Games server auth code names: [GooglePlayGames] in production. */
fun interface PlayGamesVerifier {
    /** Never throws but for its caller's cancellation. */
    suspend fun playerOf(serverAuthCode: String): PlayGamesAnswer
}

/**
 * Asks Google as [client], over [http] ([io.ntole.wyr.server.google.googleHttpClient]), the way Google's
 * server-side access for Play Games Services v2 does: the code is exchanged at Google's OAuth token
 * endpoint for an access token, which then reads the player it names from the Play Games API
 * (`players/me`). The access token is used for that one read and kept nowhere, and so is any refresh
 * token Google grants with it: the server needs nothing more of Google once it knows the player.
 *
 * Never logged: the code, the client secret, or an access token. A failure is logged by status and
 * Google's error code alone.
 */
class GooglePlayGames(
    private val client: PlayGamesClient,
    private val http: HttpClient,
) : PlayGamesVerifier {
    override suspend fun playerOf(serverAuthCode: String): PlayGamesAnswer =
        try {
            when (val exchanged = exchange(serverAuthCode)) {
                is Exchange.Granted -> playerOfToken(exchanged.accessToken)
                is Exchange.Denied -> exchanged.answer
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            log.warn("Google could not be asked who a Play Games code names: ${failed::class.java.simpleName}")
            PlayGamesAnswer.Unavailable
        }

    /** What exchanging a code came to: an access token, or the answer Google's refusal comes to. */
    private sealed interface Exchange {
        data class Granted(
            val accessToken: String,
        ) : Exchange

        data class Denied(
            val answer: PlayGamesAnswer,
        ) : Exchange
    }

    private suspend fun exchange(serverAuthCode: String): Exchange {
        val response =
            http.submitForm(
                url = TOKEN_URI,
                formParameters =
                    parameters {
                        append("grant_type", "authorization_code")
                        append("code", serverAuthCode)
                        append("client_id", client.clientId)
                        append("client_secret", client.clientSecret)
                        // Empty, as Google's own example has it for a code an installed app got.
                        append("redirect_uri", "")
                    },
            )
        if (!response.status.isSuccess()) {
            val error = response.oauthErrorCode()
            // invalid_grant is the code's fault; anything else, invalid_client above all, is this server's.
            if (error == INVALID_GRANT) return Exchange.Denied(PlayGamesAnswer.Refused)
            log.warn(
                "Google refused to exchange a Play Games code: ${response.status.value} ${error ?: "no error code"}",
            )
            return Exchange.Denied(PlayGamesAnswer.Unavailable)
        }
        return Exchange.Granted(GoogleJson.decodeFromString<TokenResponse>(response.bodyAsText()).accessToken)
    }

    private suspend fun playerOfToken(accessToken: String): PlayGamesAnswer {
        val response = http.get(PLAYERS_ME) { bearerAuth(accessToken) }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            // The code's grant does not reach Play Games, a scope the client did not ask for, say.
            log.info("Play Games refused the access a code granted: ${response.status.value}")
            return PlayGamesAnswer.Refused
        }
        if (!response.status.isSuccess()) {
            log.warn("Play Games did not name the player: ${response.status.value}")
            return PlayGamesAnswer.Unavailable
        }
        val playerId = GoogleJson.decodeFromString<PlayerResponse>(response.bodyAsText()).playerId
        if (playerId == null || !isSubject(playerId)) {
            log.warn("Play Games named its player with no id this server can keep")
            return PlayGamesAnswer.Unavailable
        }
        return PlayGamesAnswer.Player(playerId)
    }

    private fun isSubject(id: String): Boolean =
        id.isNotEmpty() && id.length <= Identities.MAX_SUBJECT_LENGTH && id.all { it in VISIBLE_ASCII }

    @Serializable
    private class TokenResponse(
        @SerialName("access_token") val accessToken: String,
    )

    @Serializable
    private class PlayerResponse(
        val playerId: String? = null,
    )

    private companion object {
        val log = LoggerFactory.getLogger(GooglePlayGames::class.java)

        const val TOKEN_URI = "https://oauth2.googleapis.com/token"
        const val PLAYERS_ME = "https://games.googleapis.com/games/v1/players/me"
        const val INVALID_GRANT = "invalid_grant"
        val VISIBLE_ASCII = '!'..'~'
    }
}

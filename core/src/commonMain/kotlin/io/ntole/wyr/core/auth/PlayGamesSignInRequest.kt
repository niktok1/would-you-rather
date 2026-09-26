package io.ntole.wyr.core.auth

import kotlinx.serialization.Serializable

/**
 * Signs in with Google Play Games Services v2 (CLAUDE.md §8a, *Play Games sign-in*), with a POST to
 * [io.ntole.wyr.core.api.WyrApi.Paths.AUTH_PLAY_GAMES]: the one-time [serverAuthCode] Play Games gave
 * the app for this server (`requestServerSideAccess`). The server exchanges it with Google for the
 * Play Games player it names, so nothing the client says about who the player is is taken on trust.
 *
 * The code is spent by that exchange, and expires within minutes: a client asks Play Games for a new
 * one for every sign-in. It is at most [io.ntole.wyr.core.api.WyrApi.Limits.MAX_SERVER_AUTH_CODE_LENGTH]
 * of visible ASCII. [toString] shows none of it, so nothing that prints the request can log it.
 */
@Serializable
public data class PlayGamesSignInRequest(
    public val serverAuthCode: String,
) {
    public override fun toString(): String = "PlayGamesSignInRequest(serverAuthCode=***)"
}

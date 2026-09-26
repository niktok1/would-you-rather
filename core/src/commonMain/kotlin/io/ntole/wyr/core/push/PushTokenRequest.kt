package io.ntole.wyr.core.push

import kotlinx.serialization.Serializable

/**
 * Registers this device's push token for the session player (CLAUDE.md §8a, *Push tokens*), with a
 * POST to [io.ntole.wyr.core.api.WyrApi.Paths.MY_PUSH_TOKENS]: the Firebase Cloud Messaging
 * registration [token] the platform gave the app, and the [platform] it is for. From then on the
 * player's pushes, such as a moderator's decision on their question, reach this device too.
 *
 * Carries no player identity, for the reason [io.ntole.wyr.core.vote.VoteRequest] does not: the
 * player, and the session the token is kept under, are whoever and whichever the request's bearer
 * token names. [token] must not be blank, and is at most
 * [io.ntole.wyr.core.api.WyrApi.Limits.MAX_PUSH_TOKEN_LENGTH] of visible ASCII, which every token
 * Firebase gives is. [platform] must name one; [PushPlatform.UNKNOWN], its default, is refused.
 */
@Serializable
public data class PushTokenRequest(
    public val token: String,
    public val platform: PushPlatform = PushPlatform.UNKNOWN,
)

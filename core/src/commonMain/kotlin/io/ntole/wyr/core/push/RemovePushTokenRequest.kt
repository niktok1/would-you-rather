package io.ntole.wyr.core.push

import kotlinx.serialization.Serializable

/**
 * Stops the session player's pushes reaching this device (CLAUDE.md §8a, *Push tokens*), with a POST to
 * [io.ntole.wyr.core.api.WyrApi.Paths.MY_PUSH_TOKEN_REMOVALS]: the [token] a [PushTokenRequest]
 * registered, held to the same rules. For a player who turns notifications off; a logout needs none,
 * since it removes the tokens of the session it ends by itself.
 */
@Serializable
public data class RemovePushTokenRequest(
    public val token: String,
)

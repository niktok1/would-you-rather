package io.ntole.wyr.core.player

import kotlinx.serialization.Serializable

/**
 * A moderator deleting a player's account on the player's request
 * ([io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_ACCOUNT_DELETIONS], CLAUDE.md §8a, *Deleting an account*),
 * naming the player by exactly one of the two: [username], compared as a login compares it, trimmed and
 * lower-cased, or [accountId], the player's id, which the game shows on its About screen, for a guest
 * or a player signed in with Play Games alone, who has no username.
 */
@Serializable
public data class DeleteAccountRequest(
    public val username: String? = null,
    public val accountId: String? = null,
)

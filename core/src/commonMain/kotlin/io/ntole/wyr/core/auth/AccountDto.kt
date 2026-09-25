package io.ntole.wyr.core.auth

import kotlinx.serialization.Serializable

/**
 * The account a registration made (CLAUDE.md §8b, *Accounts*): its [username] as the server keeps it,
 * lower-cased, which is how the player logs in with it anywhere.
 */
@Serializable
public data class AccountDto(
    public val username: String,
)

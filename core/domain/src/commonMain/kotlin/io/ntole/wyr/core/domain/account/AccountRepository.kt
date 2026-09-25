package io.ntole.wyr.core.domain.account

/**
 * The player's account on this device (CLAUDE.md §8a, *Accounts*). Implemented in `:core:data`.
 *
 * A player starts as a guest and may register, keeping everything; a registered player logs in on
 * another device with the same username and password. Whether this device's player is registered,
 * and as whom, is in their stats (`PlayerStats.username`), read with the points.
 */
public interface AccountRepository {
    /**
     * Registers the session player, a guest, as an account with [username] and [password], keeping
     * their points and everything else. Returns the username as the server keeps it, lower-cased.
     *
     * A session the server has stopped accepting is replaced first, as for any call, so the guest
     * registered is then a fresh one.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure:
     *   [io.ntole.wyr.core.domain.error.DomainError.USERNAME_TAKEN] for a name another player has,
     *   [io.ntole.wyr.core.domain.error.DomainError.ALREADY_REGISTERED] for a player registered
     *   already, which a registration whose answer was lost gets when sent again, and
     *   [io.ntole.wyr.core.domain.error.DomainError.INVALID_USERNAME] or
     *   [io.ntole.wyr.core.domain.error.DomainError.INVALID_PASSWORD] for what [AccountRules] refuses.
     */
    public suspend fun register(
        username: String,
        password: String,
    ): String

    /**
     * Logs this device in to the account [username] and [password] name: a new session of that
     * player's is stored in place of the one this device held. A guest's is left behind, and its points
     * with it. The account's other devices stay logged in.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure, the stored session left as
     *   it was: [io.ntole.wyr.core.domain.error.DomainError.INVALID_LOGIN] for a username and password
     *   that name no account, whichever of the two is wrong.
     */
    public suspend fun logIn(
        username: String,
        password: String,
    )

    /**
     * Logs this device out: tells the server, best effort, then drops the stored session whatever the
     * server answered, so the next call plays as a fresh guest. The account's other devices stay
     * logged in, and so does the session here as far as the server knows, if it never heard, until its
     * refresh token expires unused.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException only when the session could not be dropped.
     */
    public suspend fun logOut()
}

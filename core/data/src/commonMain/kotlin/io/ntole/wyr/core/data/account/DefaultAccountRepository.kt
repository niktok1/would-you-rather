package io.ntole.wyr.core.data.account

import io.ntole.wyr.core.auth.LoginRequest
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.withSessionRecovery
import io.ntole.wyr.core.domain.account.AccountRepository
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.network.api.AuthApi

/**
 * Registers, logs in and logs out through [AuthApi] (CLAUDE.md §8a, *Accounts*). The session each
 * leaves is stored as every session is, so a logged-in player stays logged in across launches.
 *
 * - A registration needs the guest's bearer, so it goes through [withSessionRecovery] as a vote does:
 *   on a dead session the retry registers the fresh guest minted for it.
 * - A login needs none, and never goes through it: its 401 is a wrong password, not a dead session.
 *   `AuthApi.logIn` keeps the bearer plugin from refreshing on it too. Only a login the server took
 *   changes the stored session.
 * - A logout is best effort: whatever the server answers, or if it never answers, the session is
 *   dropped here, and the next call mints a fresh guest.
 */
public class DefaultAccountRepository(
    private val api: AuthApi,
    private val session: DefaultSessionRepository,
) : AccountRepository {
    override suspend fun register(
        username: String,
        password: String,
    ): String = session.withSessionRecovery { api.register(RegisterRequest(username, password)) }.username

    override suspend fun logIn(
        username: String,
        password: String,
    ) {
        val loggedIn = runApi { api.logIn(LoginRequest(username, password)) }
        session.replace(loggedIn)
    }

    override suspend fun logOut() {
        // No session, nothing to end: the request would only be refused.
        if (session.storedSession() == null) return
        try {
            runApi { api.logOut() }
        } catch (unheard: WyrException) {
            // Best effort. The server keeps the session until its refresh token expires unused, and this
            // device forgets it all the same.
        }
        session.clear()
    }
}

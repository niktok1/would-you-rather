package io.ntole.wyr.core.domain.account

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Registers the session player as an account, guaranteeing a session exists first: on a cold first
 * launch there is none yet, and the guest registered is the one it mints.
 *
 * A username or password [AccountRules] refuses is refused here, before the session is ensured, so
 * nothing is sent. A form checks the rules itself, to say what is wrong, and never asks for this then.
 */
public class RegisterAccount(
    private val accounts: AccountRepository,
    private val session: SessionRepository,
) {
    /** Returns the username as the server keeps it, lower-cased. */
    public suspend operator fun invoke(
        username: String,
        password: String,
    ): String {
        // Neither message holds what was typed: a password must never reach a log.
        require(AccountRules.usernameProblem(username) == null) { "a username the account rules refuse" }
        require(AccountRules.passwordProblem(password) == null) { "a password the account rules refuse" }
        session.ensure()
        return accounts.register(username, password)
    }
}

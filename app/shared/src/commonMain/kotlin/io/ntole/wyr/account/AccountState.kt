package io.ntole.wyr.account

import io.ntole.wyr.core.domain.account.AccountRules
import io.ntole.wyr.core.domain.account.PasswordProblem
import io.ntole.wyr.core.domain.account.UsernameProblem
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.player.PlayerStats
import kotlin.time.Duration

/**
 * What the Account screen shows (CLAUDE.md §8d, *The Account screen*): who is playing on this
 * device and their stats, and for a guest the Register and Log in forms, for a registered player Log
 * out.
 *
 * What is typed lives here, in memory, and never in saved state: a password must not be written to
 * disk. It stays in the fields until they leave the screen, which is when the platform's password
 * manager, told what the fields hold (autofill content types), offers to save it.
 */
data class AccountState(
    /** The player as last read: their username, null for a guest, and their stats. Null until read. */
    val stats: PlayerStats? = null,
    val registerUsername: String = "",
    val registerPassword: String = "",
    val showRegisterPassword: Boolean = false,
    val loginUsername: String = "",
    val loginPassword: String = "",
    /**
     * The points a login would leave behind on this guest, while the one warning about it is up: null
     * when it is not. A second Log in goes ahead.
     */
    val guestPointsWarning: Int? = null,
    /** What the last action ended in, when it failed, and which it was, so its section can say so. */
    val failure: AccountFailure? = null,
    /** The action in flight, or null when idle. Only one runs at a time. */
    val running: AccountAction? = null,
) {
    val isBusy: Boolean get() = running != null

    /** What the rules refuse in the username typed to register, or null while nothing is typed. */
    val usernameProblem: UsernameProblem?
        get() = registerUsername.takeIf { it.isNotEmpty() }?.let(AccountRules::usernameProblem)

    /** What the rules refuse in the password typed to register, or null while nothing is typed. */
    val passwordProblem: PasswordProblem?
        get() = registerPassword.takeIf { it.isNotEmpty() }?.let(AccountRules::passwordProblem)

    /** Whether Register can go: both fields typed, neither refused by the rules, nothing in flight. */
    val canRegister: Boolean
        get() =
            !isBusy && registerUsername.isNotEmpty() && registerPassword.isNotEmpty() &&
                usernameProblem == null && passwordProblem == null

    /** Whether Log in can go: both fields typed and nothing in flight. The rest is the server's word. */
    val canLogIn: Boolean
        get() = !isBusy && loginUsername.isNotEmpty() && loginPassword.isNotEmpty()

    /** The points a login would leave behind, for a guest who has any, or null. */
    val pointsLeftBehindByLogIn: Int?
        get() = stats?.takeIf { it.username == null && it.totalPoints > 0 }?.totalPoints
}

/** What the Account screen can be busy doing. */
enum class AccountAction {
    /** Reading who is playing and their stats. */
    LOAD,
    REGISTER,
    LOG_IN,
    LOG_OUT,
}

/**
 * How [action] failed. [retryAfter] is the wait the server named with a
 * [DomainError.RATE_LIMITED], or null.
 */
data class AccountFailure(
    val action: AccountAction,
    val error: DomainError,
    val retryAfter: Duration? = null,
)

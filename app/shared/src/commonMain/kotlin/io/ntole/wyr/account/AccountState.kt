package io.ntole.wyr.account

import io.ntole.wyr.core.domain.account.AccountRules
import io.ntole.wyr.core.domain.account.PasswordProblem
import io.ntole.wyr.core.domain.account.UsernameProblem
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.submission.Submission
import kotlin.time.Duration

/**
 * What the Account screen and the Auth page opened from it show (CLAUDE.md §8d, *The Account
 * screen*): who is playing on this device and their stats, the questions they submitted, for a guest
 * the Register or the Log in form, whichever [authMode] names, and for a registered player Log out.
 *
 * What is typed lives here, in memory, and never in saved state: a password must not be written to
 * disk. It stays in the fields until they leave the screen, which is when the platform's password
 * manager, told what the fields hold (autofill content types), offers to save it.
 */
data class AccountState(
    /** The player as last read: their username, null for a guest, and their stats. Null until read. */
    val stats: PlayerStats? = null,
    /**
     * The questions the player submitted, newest first, as last read: My questions. Null until a read
     * works, and again once another player plays here, whose list is theirs.
     */
    val submissions: List<Submission>? = null,
    val registerUsername: String = "",
    val registerPassword: String = "",
    val showRegisterPassword: Boolean = false,
    val loginUsername: String = "",
    val loginPassword: String = "",
    /** Which form the Auth page shows: Register first, with a link to Log in and back. */
    val authMode: AuthMode = AuthMode.REGISTER,
    /**
     * The points a login would leave behind on this guest, while the one warning about it is up: null
     * when it is not. A second Log in goes ahead.
     */
    val guestPointsWarning: Int? = null,
    /** What the last action ended in, when it failed, and which it was, so its section can say so. */
    val failure: AccountFailure? = null,
    /**
     * Why the last read of My questions failed, until the next action starts. It shows under the list,
     * with Try again, whatever became of the rest of the read.
     */
    val listFailure: AccountFailure? = null,
    /** The action in flight, or null when idle. Only one runs at a time. */
    val running: AccountAction? = null,
    /**
     * A register or a login worked, as its answer said or, when the answer was lost, as the read after
     * it names an account, and the Auth page has not gone back to the Account screen for it yet
     * ([AccountActions.leftAuth]). The next action takes it down too, so it is never left over for a
     * later showing of the page.
     */
    val signedIn: Boolean = false,
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

    /**
     * Whether Log in can go: both fields typed, a player read, whose points the one warning names,
     * and nothing in flight. The rest is the server's word.
     */
    val canLogIn: Boolean
        get() = !isBusy && stats != null && loginUsername.isNotEmpty() && loginPassword.isNotEmpty()

    /**
     * The points a login would leave behind, for a player with any who has no username: a guest, or
     * one registered by Play Games alone, whose account stays, but not on this device. Null otherwise.
     */
    val pointsLeftBehindByLogIn: Int?
        get() = stats?.takeIf { it.username == null && it.totalPoints > 0 }?.totalPoints
}

/** The Auth page's two forms (CLAUDE.md §8d, *The Account screen*). */
enum class AuthMode {
    REGISTER,
    LOG_IN,
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

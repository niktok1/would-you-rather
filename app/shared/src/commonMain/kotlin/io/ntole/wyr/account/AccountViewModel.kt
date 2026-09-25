package io.ntole.wyr.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.account.LogIn
import io.ntole.wyr.core.domain.account.LogOut
import io.ntole.wyr.core.domain.account.RegisterAccount
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Account screen and the Auth page can ask for, so each takes one argument for all of it. */
interface AccountActions {
    /** Reads who is playing and their stats again. */
    fun refresh()

    /** The Auth page is shown: reads the player if none is read yet, so a login knows their points. */
    fun authShown()

    /** Shows the Auth page's other form. Ignored while an action runs. */
    fun setAuthMode(mode: AuthMode)

    /** The Auth page went back to the Account screen for [AccountState.signedIn]: takes it down. */
    fun leftAuth()

    fun setRegisterUsername(text: String)

    fun setRegisterPassword(text: String)

    fun toggleShowRegisterPassword()

    fun register()

    fun setLoginUsername(text: String)

    fun setLoginPassword(text: String)

    fun logIn()

    /** Takes the guest-progress warning down, and logs in to nothing. */
    fun cancelLogIn()

    fun logOut()
}

/**
 * Drives the Account screen and the Auth page opened from it (CLAUDE.md §8d, *The Account screen*):
 * register the guest playing, log in to an account, log out, each through its use case, and the
 * player read after every one, their stats and then the questions they submitted (My questions). A
 * register or a login that worked raises [AccountState.signedIn], for the Auth page to go back on.
 *
 * One action at a time, and after each the player is read again, whatever became of it: a
 * registration whose answer was lost may have landed, and the read then names the account.
 */
class AccountViewModel(
    private val getPlayerStats: GetPlayerStats,
    private val getMySubmissions: GetMySubmissions,
    private val registerAccount: RegisterAccount,
    private val logInToAccount: LogIn,
    private val logOutOfAccount: LogOut,
) : ViewModel(),
    AccountActions {
    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /**
     * Not read on creation: the screen asks every time it is shown, since the stats move on the Play
     * screen meanwhile, and a guest's points are what a login would leave behind.
     */
    override fun refresh() = perform(AccountAction.LOAD) {}

    /** Only when nothing is read: a guest's points are what the one warning before a login is about. */
    override fun authShown() {
        if (_state.value.stats == null) refresh()
    }

    /**
     * The other form takes the warning down, and a failure of either form with it, which would
     * otherwise be back when the player returns to the form it came from.
     */
    override fun setAuthMode(mode: AuthMode) =
        _state.update {
            if (it.isBusy) {
                it
            } else {
                it.copy(
                    authMode = mode,
                    guestPointsWarning = null,
                    failure = it.failure?.takeUnless { failure -> failure.action in AUTH_ACTIONS },
                )
            }
        }

    override fun leftAuth() = _state.update { it.copy(signedIn = false) }

    override fun setRegisterUsername(text: String) = _state.update { it.copy(registerUsername = text) }

    override fun setRegisterPassword(text: String) = _state.update { it.copy(registerPassword = text) }

    override fun toggleShowRegisterPassword() =
        _state.update { it.copy(showRegisterPassword = !it.showRegisterPassword) }

    /** Registers the guest playing, keeping their points. Nothing happens until [AccountState.canRegister]. */
    override fun register() {
        val draft = _state.value
        if (!draft.canRegister) return
        perform(AccountAction.REGISTER) {
            registerAccount(draft.registerUsername, draft.registerPassword)
            _state.update { it.copy(signedIn = true) }
        }
    }

    override fun setLoginUsername(text: String) = _state.update { it.copy(loginUsername = text) }

    override fun setLoginPassword(text: String) = _state.update { it.copy(loginPassword = text) }

    /**
     * Logs this device in to the account typed. A guest with points is warned once that they stay
     * behind, and the next Log in goes ahead. Nothing happens until [AccountState.canLogIn].
     */
    override fun logIn() {
        val draft = _state.value
        if (!draft.canLogIn) return
        val pointsLeftBehind = draft.pointsLeftBehindByLogIn
        if (pointsLeftBehind != null && draft.guestPointsWarning == null) {
            _state.update { it.copy(guestPointsWarning = pointsLeftBehind, failure = null) }
            return
        }
        perform(AccountAction.LOG_IN) {
            logInToAccount(draft.loginUsername, draft.loginPassword)
            // Another player from here on: what was read was the guest's.
            _state.update { it.copy(stats = null, submissions = null, guestPointsWarning = null, signedIn = true) }
        }
    }

    override fun cancelLogIn() = _state.update { it.copy(guestPointsWarning = null) }

    /** Logs this device out, best effort; it plays on as a fresh guest, whom the read after mints. */
    override fun logOut() =
        perform(AccountAction.LOG_OUT) {
            logOutOfAccount()
            _state.update { it.copy(stats = null, submissions = null) }
        }

    /**
     * Runs [block] as the one action in flight, then reads the player again, a failed action's too.
     * A second action while one runs is ignored.
     */
    private fun perform(
        action: AccountAction,
        block: suspend () -> Unit,
    ) {
        if (_state.value.isBusy) return
        _state.update { it.copy(running = action, failure = null, listFailure = null, signedIn = false) }

        viewModelScope.launch {
            try {
                try {
                    block()
                } catch (failure: WyrException) {
                    _state.update { it.copy(failure = AccountFailure(action, failure.error, failure.retryAfter)) }
                }
                load()
            } finally {
                _state.update { it.copy(running = null) }
            }
        }
    }

    /**
     * Reads who is playing, minting a guest where there is none, then the questions they submitted,
     * each whatever became of the other. A registered player sees no form, so what was typed goes
     * then: not before, since the platform's password manager reads the fields as they leave the
     * screen, and the Auth page leaves on [AccountState.signedIn], which stays up for it. A failed
     * read keeps what was shown, and says so: the stats' unless the action before it already failed,
     * which says more, and the list's under the list.
     */
    private suspend fun load() {
        loadStats()
        try {
            val submissions = getMySubmissions()
            _state.update { it.copy(submissions = submissions) }
        } catch (failure: WyrException) {
            _state.update {
                it.copy(
                    listFailure = AccountFailure(AccountAction.LOAD, failure.error, failure.retryAfter),
                )
            }
        }
    }

    private suspend fun loadStats() {
        try {
            val stats = getPlayerStats()
            _state.update {
                if (stats.username == null) {
                    it.copy(stats = stats)
                } else {
                    AccountState(
                        stats = stats,
                        submissions = it.submissions,
                        failure = it.failure,
                        running = it.running,
                        signedIn = it.signedIn,
                    )
                }
            }
        } catch (failure: WyrException) {
            _state.update {
                it.copy(failure = it.failure ?: AccountFailure(AccountAction.LOAD, failure.error, failure.retryAfter))
            }
        }
    }
}

/** The actions the Auth page's forms send, whose failures show under them. */
private val AUTH_ACTIONS = setOf(AccountAction.REGISTER, AccountAction.LOG_IN)

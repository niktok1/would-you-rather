package io.ntole.wyr.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.account.LogIn
import io.ntole.wyr.core.domain.account.LogOut
import io.ntole.wyr.core.domain.account.RegisterAccount
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.playgames.LinkPlayGames
import io.ntole.wyr.core.domain.session.CurrentSession
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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

    /** Signs in with Google Play Games Services, where [AccountState.offersPlayGames]. */
    fun signInWithPlayGames() = Unit

    fun logOut()
}

/**
 * Drives the Account screen and the Auth page opened from it (CLAUDE.md §8d, *The Account screen*):
 * register the guest playing, log in to an account, log out, each through its use case, and the
 * player read after every one, their stats and then the questions they submitted (My questions). A
 * register or a login that worked raises [AccountState.signedIn], for the Auth page to go back on.
 *
 * One action at a time, and after each the player is read again, whatever became of it: a
 * registration whose answer was lost may have landed, and the read then names the account. The device
 * can become another player with nothing asked here, by the launch's Play Games sign-in or a dead
 * session replaced ([session]): what was read is then dropped and read again, as the player now.
 *
 * [analytics] hear of it all (CLAUDE.md §8g): the screen opened, a registration sent and one that
 * worked, a login that worked, a logout, and every failure shown, by its code; never what was typed.
 */
class AccountViewModel(
    private val getPlayerStats: GetPlayerStats,
    private val getMySubmissions: GetMySubmissions,
    private val registerAccount: RegisterAccount,
    private val logInToAccount: LogIn,
    private val logOutOfAccount: LogOut,
    private val analytics: Analytics,
    private val linkPlayGames: LinkPlayGames,
    private val session: CurrentSession,
) : ViewModel(),
    AccountActions {
    private val _state = MutableStateFlow(AccountState(playGamesAvailable = linkPlayGames.available))
    val state: StateFlow<AccountState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            session.sessions.collectLatest { player ->
                // An action in flight reads the player itself once done, its own login or logout included.
                _state.first { !it.isBusy }
                if (_state.value.readFor.let { it != null && it != player }) refresh()
            }
        }
    }

    /**
     * Not read on creation: the screen asks every time it is shown, since the stats move on the Play
     * screen meanwhile, and a guest's points are what a login would leave behind.
     */
    override fun refresh() = perform(AccountAction.LOAD) {}

    /**
     * The Account screen is shown: the player is read again, and the analytics hear of it when that
     * begins a [newVisit], not when a rotation's composition shows the same one (CLAUDE.md §8g).
     */
    fun shown(newVisit: Boolean = true) {
        if (newVisit) analytics.track(AnalyticsEvent.ACCOUNT_OPENED)
        refresh()
    }

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
            analytics.track(AnalyticsEvent.REGISTER_STARTED)
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

    /**
     * Signs in with Play Games, asking the player to sign in to it first unless they are: the player
     * playing is linked to it, keeping everything, or the device is the player it was linked to
     * already. A player who does not sign in to Play Games stays on the page, with nothing sent.
     */
    override fun signInWithPlayGames() {
        if (!_state.value.offersPlayGames) return
        perform(AccountAction.PLAY_GAMES) {
            if (linkPlayGames.manually()) {
                // Maybe another player from here on, as after a login: what was read may be the guest's.
                _state.update { it.copy(stats = null, submissions = null, signedIn = true) }
            }
        }
    }

    /** Logs this device out, best effort; it plays on as a fresh guest, whom the read after mints. */
    override fun logOut() =
        perform(AccountAction.LOG_OUT) {
            // Before the logout, which has the analytics forget who the player was: the event is theirs.
            analytics.track(AnalyticsEvent.LOGOUT)
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
                val worked =
                    try {
                        block()
                        true
                    } catch (failure: WyrException) {
                        _state.update { it.copy(failure = AccountFailure(action, failure.error, failure.retryAfter)) }
                        false
                    }
                val confirmed = load()
                // Not from signedIn, which the Auth page takes down as it leaves, while the read runs.
                report(action, completed = worked || confirmed)
            } finally {
                _state.update { it.copy(running = null) }
            }
        }
    }

    /**
     * What [action] ended in, once the player was read after it, for the analytics: a register or a
     * login that [completed], its answer lost or not, and every failure the screens show.
     */
    private fun report(
        action: AccountAction,
        completed: Boolean,
    ) {
        val settled = _state.value
        if (completed) {
            when (action) {
                AccountAction.REGISTER -> analytics.track(AnalyticsEvent.REGISTER_COMPLETED)
                AccountAction.LOG_IN -> analytics.track(AnalyticsEvent.LOGIN_COMPLETED)
                else -> Unit
            }
        }
        settled.failure?.let { reportShown(it, actionName(it.action)) }
        settled.listFailure?.let { reportShown(it, ACTION_MY_QUESTIONS) }
    }

    private fun reportShown(
        failure: AccountFailure,
        action: String,
    ) {
        analytics.track(
            AnalyticsEvent.ERROR_SHOWN,
            mapOf(AnalyticsProperty.CODE to failure.error.name, AnalyticsProperty.ACTION to action),
        )
    }

    /**
     * Reads who is playing, minting a guest where there is none, then the questions they submitted,
     * each whatever became of the other. A registered player sees no form, so what was typed goes
     * then: not before, since the platform's password manager reads the fields as they leave the
     * screen, and the Auth page leaves on [AccountState.signedIn], which stays up for it. A register
     * or a login whose answer was lost worked all the same when the read after it names an account:
     * it raises [AccountState.signedIn] too, and its failure goes. A failed read keeps what was
     * shown, and says so: the stats' unless the action before it already failed, which says more,
     * and the list's under the list. It answers whether the read shows the register or the login in
     * flight to have worked.
     */
    private suspend fun load(): Boolean {
        // Named before anything is read, so a list read as a player the device became meanwhile is not
        // this one's (AccountState.readFor), and nothing of another player's stays, the read failing or
        // not.
        val before = session.current()
        if (before != null) {
            _state.update {
                if (it.readFor == before) it else it.copy(stats = null, submissions = null, readFor = before)
            }
        }
        val confirmed = loadStats()
        // None was stored: a first launch's, or a logout's, whose guest the read of the stats minted.
        if (before == null) _state.update { it.copy(submissions = null, readFor = session.current()) }
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
        return confirmed
    }

    /**
     * Reads who is playing, and answers whether that shows the Auth page's action in flight to have
     * worked: a register or a login, the player has a username now, and a Play Games sign-in, they are
     * linked to it. Only a player with no username reaches the Auth page, a guest or one registered by
     * Play Games alone (CLAUDE.md §8a), and one linked to it is offered no Play Games, so a failed one
     * leaves neither.
     */
    private suspend fun loadStats(): Boolean =
        try {
            val stats = getPlayerStats()
            var confirmed = false
            _state.update {
                confirmed =
                    when (it.running) {
                        AccountAction.REGISTER, AccountAction.LOG_IN -> stats.username != null
                        AccountAction.PLAY_GAMES -> stats.playGamesLinked
                        else -> false
                    }
                if (stats.username == null && !confirmed) {
                    it.copy(stats = stats)
                } else {
                    AccountState(
                        stats = stats,
                        submissions = it.submissions,
                        readFor = it.readFor,
                        failure = it.failure.takeUnless { confirmed },
                        running = it.running,
                        signedIn = it.signedIn || confirmed,
                        playGamesAvailable = it.playGamesAvailable,
                    )
                }
            }
            confirmed
        } catch (failure: WyrException) {
            _state.update {
                it.copy(failure = it.failure ?: AccountFailure(AccountAction.LOAD, failure.error, failure.retryAfter))
            }
            false
        }
}

/** The actions the Auth page sends, whose failures show under what sent them. */
private val AUTH_ACTIONS = setOf(AccountAction.REGISTER, AccountAction.LOG_IN, AccountAction.PLAY_GAMES)

/** What failed, as the analytics name it (CLAUDE.md §8g). */
private fun actionName(action: AccountAction): String =
    when (action) {
        AccountAction.LOAD -> "account"
        AccountAction.REGISTER -> "register"
        AccountAction.LOG_IN -> "log_in"
        AccountAction.PLAY_GAMES -> "play_games"
        AccountAction.LOG_OUT -> "log_out"
    }

private const val ACTION_MY_QUESTIONS = "my_questions"

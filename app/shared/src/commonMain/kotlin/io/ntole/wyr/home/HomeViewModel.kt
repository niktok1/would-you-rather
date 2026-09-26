package io.ntole.wyr.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.home.GetHomePicks
import io.ntole.wyr.core.domain.home.PickOnHome
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Home screen's two Play buttons (CLAUDE.md §8d, *Home picks*): how many picked each, read
 * each time Home is shown ([shown]), and each tap counted ([pick]), both best effort. Neither says
 * anything when it fails: the buttons start the game all the same.
 */
class HomeViewModel(
    private val getHomePicks: GetHomePicks,
    private val pickOnHome: PickOnHome,
) : ViewModel() {
    private val _picks = MutableStateFlow<Tally?>(null)

    /**
     * Every player's taps on each button, as the server last reported them, or null until it has. A
     * read that fails keeps what was read before.
     */
    val picks: StateFlow<Tally?> = _picks.asStateFlow()

    /** The read in flight, if any: a second showing replaces it. */
    private var read: Job? = null

    /** Home is shown: the counts move with every player's taps, so they are read again. */
    fun shown() {
        read?.cancel()
        read =
            viewModelScope.launch {
                try {
                    _picks.value = getHomePicks()
                } catch (unread: WyrException) {
                    // Kept as they were (above). Only a WyrException: a cancellation must go on up.
                }
            }
    }

    /**
     * Counts the player's tap on [side]'s button, in the background: the game it starts opens at once,
     * whatever becomes of this, and a tap that is not counted says nothing. Its answer is not shown:
     * Home, left by then, reads the counts again when it is shown next.
     */
    fun pick(side: Side) {
        viewModelScope.launch {
            try {
                pickOnHome(side)
            } catch (uncounted: WyrException) {
                // Best effort (above). Only a WyrException: a cancellation must go on up.
            }
        }
    }
}

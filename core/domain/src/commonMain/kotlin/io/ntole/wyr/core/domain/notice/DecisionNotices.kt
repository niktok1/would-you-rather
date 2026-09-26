package io.ntole.wyr.core.domain.notice

import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.session.CurrentSession
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * That a moderator decided one of the player's questions since they last saw My questions (CLAUDE.md
 * §8d, *Submitting*): the in-app notice, pushes or none, on every platform.
 *
 * [check] reads the player's questions, when a session is stored, and [unseen] holds those decided,
 * approved or rejected (a retired one was approved), that this device has not shown them yet
 * ([SeenDecisionsStore], one player's at a time): what badges the account icon. [shown] is the Account
 * screen's list, which marks every decision in it seen and answers the ones new to the player, for
 * their rows to say so while it is shown. The first read of a player's on a device seeds what is seen,
 * so nothing decided before badges.
 *
 * Nothing here mints a session, and a read that fails changes nothing.
 */
public class DecisionNotices(
    private val submissions: SubmissionRepository,
    private val session: CurrentSession,
    private val seen: SeenDecisionsStore,
) {
    private val mutex = Mutex()
    private val _unseen = MutableStateFlow<Set<String>>(emptySet())

    /** The ids of the player's questions decided since they last saw My questions. */
    public val unseen: StateFlow<Set<String>> = _unseen.asStateFlow()

    /** Reads the player's questions, if a session is stored, and updates [unseen]. Never throws but cancellation. */
    public suspend fun check() {
        val player = session.current()
        if (player == null) {
            _unseen.value = emptySet()
            return
        }
        val listed =
            try {
                submissions.mine()
            } catch (unread: WyrException) {
                return
            }
        compare(player, listed, markSeen = false)
    }

    /**
     * The Account screen shows [listed], the player's questions: every decision in it is seen from now
     * on, and [unseen] empties. Returns the ids decided since the player last saw them, for their rows.
     */
    public suspend fun shown(listed: List<Submission>): Set<String> {
        val player = session.current() ?: return emptySet()
        return compare(player, listed, markSeen = true)
    }

    private suspend fun compare(
        player: String,
        listed: List<Submission>,
        markSeen: Boolean,
    ): Set<String> =
        mutex.withLock {
            // Read for a player who plays here no more: a login or a logout came meanwhile.
            if (session.current() != player) return@withLock emptySet()
            val decided = listed.filter { it.status in DECIDED }.map { it.id }.toSet()
            val before = seen.read()?.takeIf { it.playerId == player }
            if (before == null) {
                // A first read of this player's here: what was decided before is not news.
                seen.write(SeenDecisions(player, decided))
                _unseen.value = emptySet()
                return@withLock emptySet()
            }
            val fresh = decided - before.questionIds
            if (markSeen) {
                if (decided != before.questionIds) seen.write(SeenDecisions(player, decided))
                _unseen.value = emptySet()
            } else {
                _unseen.value = fresh
            }
            fresh
        }

    private companion object {
        /** A moderator's decision: approved, rejected, or retired, which was approved first. */
        val DECIDED = setOf(SubmissionStatus.APPROVED, SubmissionStatus.REJECTED, SubmissionStatus.RETIRED)
    }
}

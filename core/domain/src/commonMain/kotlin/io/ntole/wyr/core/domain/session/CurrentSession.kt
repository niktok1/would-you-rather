package io.ntole.wyr.core.domain.session

import kotlinx.coroutines.flow.Flow

/**
 * Who plays on this device, as its stored session names them, watched without minting anyone: for
 * what follows the session rather than asks for one, a push token registered for each session
 * (CLAUDE.md §8a, *Push tokens*), a Play Games sign-in once there is a session (*Play Games sign-in*),
 * and the decisions on a player's questions (§8d, *Submitting*). Implemented in `:core:data`, beside
 * [SessionRepository], which mints.
 */
public interface CurrentSession {
    /** The player id of the session stored now, or null when there is none: never mints. */
    public fun current(): String?

    /**
     * The player id of the session stored now, if there is one, and then of every session stored after
     * it, a mint's, a login's or a Play Games sign-in's, each once, however it came: a session of the
     * same player's again is a new one. A refresh, which keeps its session, is not. Never mints.
     */
    public val sessions: Flow<String>
}

package io.ntole.wyr.core.domain.push

import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.session.CurrentSession
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Keeps this device's push token registered for whoever plays on it (CLAUDE.md §8a, *Push tokens*):
 * the server keeps a token under the session that registered it, and a logout's cascade drops it, so
 * it is registered again for every session the device stores, a mint's, a login's, a logout's fresh
 * guest's, a Play Games sign-in's or a deletion's fresh guest's, and whenever the platform gives a new
 * token. At launch too, for the session stored then, which costs one request and mends one that failed.
 *
 * Best effort: a registration that fails is dropped, and the next session or token registers again.
 * Nothing here mints a session.
 */
public class KeepPushTokenRegistered(
    private val push: DevicePush,
    private val tokens: PushTokenRepository,
    private val session: CurrentSession,
) {
    /** Registers now and after every change, for as long as the caller runs it; nothing without pushes. */
    public suspend fun run() {
        if (!push.available) return
        merge(session.sessions.map { }, push.newTokens.map { }).collect { registerNow() }
    }

    private suspend fun registerNow() {
        // No session is nobody to register for; the next one stored is heard.
        if (session.current() == null) return
        val token = push.token() ?: return
        try {
            tokens.register(token, push.platform)
        } catch (failed: WyrException) {
            // Best effort: the next session or token registers again.
        }
    }
}

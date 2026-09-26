package io.ntole.wyr.core.domain.push

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * This device's pushes (CLAUDE.md §8a, *Push tokens*): Firebase Cloud Messaging on an Android build
 * that has it set up, and [None] everywhere else, which is never [available].
 *
 * Nothing here throws but the caller's cancellation: a failure is no token.
 */
public interface DevicePush {
    /** Whether this build has pushes at all: an Android build given its Firebase project's ids. */
    public val available: Boolean

    /** The platform the token is for, which the server keeps beside it. */
    public val platform: PushPlatform

    /** This device's push token, or null when there is none yet or the platform gave none. */
    public suspend fun token(): String?

    /** Every new token the platform gives this device from now on, which replaces the one before. */
    public val newTokens: Flow<String>

    /** Every push that arrives while the app is open, which shows no notification of its own. */
    public val received: Flow<Unit>

    /**
     * Asks the player once, ever, to let the game post notifications, where the platform asks for that
     * (Android 13 and later): at the least obstructive moment, which is the caller's to choose. Returns
     * at once; asking again does nothing.
     */
    public fun askPermissionOnce()

    public companion object {
        /** No pushes: a build or a platform without them, and the tests. */
        public val None: DevicePush = NoPush
    }
}

/** The platform a push token is for. */
public enum class PushPlatform {
    ANDROID,
    IOS,
    WEB,
}

private object NoPush : DevicePush {
    override val available: Boolean = false
    override val platform: PushPlatform = PushPlatform.ANDROID

    override suspend fun token(): String? = null

    override val newTokens: Flow<String> = emptyFlow()
    override val received: Flow<Unit> = emptyFlow()

    override fun askPermissionOnce() = Unit
}

package io.ntole.wyr.services

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * The activity on screen, for what only an activity can ask: a Play Games sign-in, and the
 * notifications permission. Registered with the application by [androidDeviceServices].
 *
 * Every activity started and not stopped yet is kept, weakly, so a destroyed one is never held, and
 * the one on screen is the most recent of them, one not finishing first. Play Games shows its sign-in
 * through a translucent activity of its own (`GamesResolutionActivity`) over the app's, which is only
 * paused and so never starts again: once that one stops, the app's is on screen again, still started.
 * None started is the app in the background, where nothing asks.
 */
internal class ActivityTracker : Application.ActivityLifecycleCallbacks {
    private val started = mutableListOf<WeakReference<Activity>>()

    /** The activity on screen now, or null when none is started: the app is in the background. */
    fun current(): Activity? =
        synchronized(started) {
            val held = started.mapNotNull { it.get() }
            held.lastOrNull { !it.isFinishing } ?: held.lastOrNull()
        }

    override fun onActivityStarted(activity: Activity) {
        synchronized(started) { started += WeakReference(activity) }
    }

    override fun onActivityStopped(activity: Activity) {
        synchronized(started) { started.removeAll { it.get().let { held -> held == null || held === activity } } }
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

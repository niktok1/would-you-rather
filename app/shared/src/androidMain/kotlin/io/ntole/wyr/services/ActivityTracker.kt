package io.ntole.wyr.services

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * The activity on screen, for what only an activity can ask: a Play Games sign-in, and the
 * notifications permission. Held weakly, so a destroyed activity is never kept, and forgotten as it
 * stops, so nothing asks one in the background.
 */
internal class ActivityTracker(
    application: Application,
) : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var started: WeakReference<Activity>? = null

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    /** The activity started now, or null when none is: the app is in the background. */
    fun current(): Activity? = started?.get()

    override fun onActivityStarted(activity: Activity) {
        started = WeakReference(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        if (started?.get() === activity) started = null
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

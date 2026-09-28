package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.spi.SdkActivity
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which host activity is on screen and whether the app is in foreground (spec §5). Lifecycle
 * callbacks arrive on the main thread; listeners are called there too. Foreground is tracked from
 * observed activity starts rather than ProcessLifecycleOwner to avoid a lifecycle-process dependency;
 * stops of activities started before registration are ignored.
 *
 * Several host activities can be resumed at once (multi-window, API 29+): they are kept in resume
 * order and the most recent one still resumed is [currentActivity]. When the current one pauses, the
 * previous one takes over and listeners get [Listener.onHostActivityBecameCurrent] for it.
 */
internal class ActivityTracker(
    private val logger: SdkLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) : Application.ActivityLifecycleCallbacks {

    interface Listener {
        fun onHostActivityResumed(activity: Activity) {}

        /**
         * [activity] was already resumed (multi-window) and is now [currentActivity] because the
         * current one paused. A resume for anyone who does not override it.
         */
        fun onHostActivityBecameCurrent(activity: Activity) = onHostActivityResumed(activity)

        fun onHostActivityPaused(activity: Activity) {}
        fun onForegroundChanged(foreground: Boolean) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    // Resume order, oldest first. Weak so a host that is destroyed without a pause (it happens only
    // on process-wide failures) is never kept alive or reported as current.
    private val resumed = ArrayList<WeakReference<Activity>>()
    private val started: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())
    private val sdkActivities: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    val currentActivity: Activity?
        get() {
            prune()
            return resumed.lastOrNull()?.get()
        }

    /** Every resumed host activity, oldest first: one, or several in multi-window (API 29+). */
    val resumedActivities: List<Activity>
        get() {
            prune()
            return resumed.mapNotNull { it.get() }
        }

    /** Whether any SDK activity is created and not yet destroyed. */
    val hasLiveSdkActivity: Boolean
        get() = sdkActivities.isNotEmpty()

    val isForeground: Boolean
        get() = started.isNotEmpty()

    /**
     * Wall clock of the first host activity resumed since registration, or null while none has been: whether a
     * user opened this process (spec §8, force restart). Set before the listeners hear that resume.
     */
    var firstHostResumedAt: Long? = null
        private set

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    override fun onActivityStarted(activity: Activity) {
        val wasForeground = started.isNotEmpty()
        started += activity
        if (!wasForeground) notify { it.onForegroundChanged(true) }
    }

    override fun onActivityStopped(activity: Activity) {
        if (!started.remove(activity)) return
        if (started.isEmpty()) notify { it.onForegroundChanged(false) }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is SdkActivity) return
        if (firstHostResumedAt == null) firstHostResumedAt = clock()
        resumed.removeAll { it.get() === activity }
        resumed += WeakReference(activity)
        notify { it.onHostActivityResumed(activity) }
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is SdkActivity) return
        val wasCurrent = currentActivity === activity
        resumed.removeAll { it.get() === activity }
        notify { it.onHostActivityPaused(activity) }
        if (!wasCurrent) return
        val fallback = currentActivity ?: return
        notify { it.onHostActivityBecameCurrent(fallback) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is SdkActivity) sdkActivities += activity
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is SdkActivity) sdkActivities -= activity
    }

    private fun prune() {
        resumed.removeAll { it.get().let { activity -> activity == null || activity.isDestroyed } }
    }

    private inline fun notify(event: (Listener) -> Unit) {
        listeners.forEach { listener ->
            try {
                event(listener)
            } catch (e: Exception) {
                logger.e("Activity tracking listener failed", e)
            }
        }
    }
}

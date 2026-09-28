package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import io.github.feedbacklib.android.InvocationEvent
import io.github.feedbacklib.android.internal.core.SdkLogger

/**
 * Keeps each detector running exactly while it should (spec §5): SDK enabled, its event enabled,
 * and — for process detectors — the app in foreground, for activity detectors — a host activity
 * resumed. Main thread.
 */
internal class InvocationManager(
    private val tracker: ActivityTracker,
    private val events: () -> Set<InvocationEvent>,
    private val isEnabled: () -> Boolean,
    private val processDetectors: Map<InvocationEvent, ProcessDetector>,
    private val activityDetectors: Map<InvocationEvent, ActivityDetector>,
    private val logger: SdkLogger,
) : ActivityTracker.Listener {

    private val attachedTo = mutableMapOf<InvocationEvent, Activity>()

    override fun onForegroundChanged(foreground: Boolean) = refresh()

    override fun onHostActivityResumed(activity: Activity) = refresh()

    override fun onHostActivityPaused(activity: Activity) {
        attachedTo.entries.removeAll { (event, attached) ->
            if (attached === activity) {
                safely { activityDetectors[event]?.detach(activity) }
                true
            } else {
                false
            }
        }
    }

    fun refresh() {
        val enabledEvents = if (isEnabled()) events() else emptySet()
        processDetectors.forEach { (event, detector) ->
            if (event in enabledEvents && tracker.isForeground) safely { detector.start() } else safely { detector.stop() }
        }
        val activity = tracker.currentActivity
        activityDetectors.forEach { (event, detector) ->
            val attached = attachedTo[event]
            val wanted = if (event in enabledEvents) activity else null
            if (attached != null && attached !== wanted) {
                safely { detector.detach(attached) }
                attachedTo.remove(event)
            }
            if (wanted != null && attachedTo[event] == null) {
                safely { detector.attach(wanted) }
                attachedTo[event] = wanted
            }
        }
    }

    fun stopAll() {
        processDetectors.values.forEach { safely { it.stop() } }
        attachedTo.forEach { (event, activity) -> safely { activityDetectors[event]?.detach(activity) } }
        attachedTo.clear()
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.e("Invocation detector failed", e)
        }
    }
}

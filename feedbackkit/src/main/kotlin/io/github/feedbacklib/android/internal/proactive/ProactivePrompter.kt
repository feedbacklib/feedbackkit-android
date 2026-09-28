package io.github.feedbacklib.android.internal.proactive

import android.app.Activity
import android.os.Handler
import android.os.Looper
import io.github.feedbacklib.android.internal.core.ProactiveSettings
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.invoke.ActivityTracker
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.internal.report.ProactiveTrigger

/**
 * Proactive reporting's prompt for the event of the previous run (spec §8): the delay after the first
 * resumed host screen once the event is known, if the feature is on and more than the gap has passed
 * since the last prompt — both as the host has them when the delay ends. An event switched off or
 * inside the gap is dropped for this process. Shown or dropped, the event is resolved through [onResolved],
 * which clears its `pending` entry; one never resolved — no host screen came, or the SDK was torn down —
 * waits for a later process. A force restart is never pending: it is offered only if this process's first
 * host screen resumed at most [ProactiveDetector.FORCE_RESTART_SCREEN_WINDOW_MILLIS] after its start, and
 * otherwise forgotten unresolved — the system, not the user, started the process. With no host screen
 * resumed, an SDK screen open or bug reporting off at that moment, the next resumed host screen starts the
 * delay again. At most one prompt per process. Main thread.
 */
internal class ProactivePrompter(
    private val tracker: ActivityTracker,
    private val settings: () -> ProactiveSettings,
    /** Opens the prompt over the current host screen; false when it cannot now (see InvocationCoordinator.showProactive). */
    private val show: (ProactiveInfo) -> Boolean,
    /** The event is settled: the prompt showed at this wall clock time, or it was dropped (null). `proactive.json` (spec §8). */
    private val onResolved: (ProactiveEvent, Long?) -> Unit,
    private val logger: SdkLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) : ActivityTracker.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: ProactiveEvent? = null
    private var done = false
    private var scheduled = false
    private val prompt = Runnable {
        scheduled = false
        try {
            fire()
        } catch (e: Exception) {
            logger.e("Could not show the proactive reporting prompt", e)
        }
    }

    /** The event of the previous run; a second one, or one after the prompt, is ignored. */
    fun offer(event: ProactiveEvent) {
        if (done || pending != null) return
        if (!userStarted(event)) return forget(event)
        pending = event
        if (tracker.currentActivity != null) schedule()
    }

    /** No prompt any more: the SDK is torn down. */
    fun cancel() {
        handler.removeCallbacks(prompt)
        scheduled = false
        pending = null
        done = true
    }

    override fun onHostActivityResumed(activity: Activity) {
        val event = pending ?: return
        if (!userStarted(event)) return forget(event)
        if (!scheduled) schedule()
    }

    /**
     * False for a force restart whose process had no host screen within the window after its start (its
     * [ProactiveEvent.detectedAt]); true while none has resumed yet — the next resume decides.
     */
    private fun userStarted(event: ProactiveEvent): Boolean {
        if (event.info.trigger != ProactiveTrigger.FORCE_RESTART) return true
        val firstScreenAt = tracker.firstHostResumedAt ?: return true
        return firstScreenAt - event.detectedAt <= ProactiveDetector.FORCE_RESTART_SCREEN_WINDOW_MILLIS
    }

    /** A force restart the system started: dropped for good and never resolved, as it was never pending. */
    private fun forget(event: ProactiveEvent) {
        logger.d("The ${event.info.trigger} of the previous run gets no prompt: no host screen within 10 s of this start")
        pending = null
        done = true
    }

    private fun schedule() {
        scheduled = true
        // Settings may be built directly, without ProactiveSettings.from and its clamp, so the Handler must never see more than MAX_DELAY_MILLIS.
        handler.postDelayed(prompt, settings().delayMillis.coerceIn(0, ProactiveSettings.MAX_DELAY_MILLIS))
    }

    private fun fire() {
        val event = pending ?: return
        val current = settings()
        if (!current.enabled) return drop(event, "proactive reporting is off")
        if (!ProactiveDetector.gapPassed(event.lastModalAt, clock(), current.gapMillis)) return drop(event, "the last prompt was too recent")
        if (tracker.currentActivity == null || !show(event.info)) {
            logger.d("The proactive reporting prompt waits for the next host screen")
            return
        }
        pending = null
        done = true
        resolved(event, clock())
    }

    private fun drop(event: ProactiveEvent, reason: String) {
        logger.d("The ${event.info.trigger} of the previous run gets no prompt: $reason")
        pending = null
        done = true
        resolved(event, null)
    }

    private fun resolved(event: ProactiveEvent, shownAt: Long?) {
        try {
            onResolved(event, shownAt)
        } catch (e: Exception) {
            logger.e("Could not record the proactive reporting prompt", e)
        }
    }
}

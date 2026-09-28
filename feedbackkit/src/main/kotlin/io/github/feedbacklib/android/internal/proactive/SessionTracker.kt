package io.github.feedbacklib.android.internal.proactive

import android.os.Handler
import android.os.Looper
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.invoke.ActivityTracker

/**
 * Keeps `session.json` current (spec §8): a heartbeat every [heartbeatMillis] while the app is in the
 * foreground; on leaving it wentBackground and lastBackgroundAt are written at once, and the heartbeat
 * goes on for [BACKGROUND_TAIL_MILLIS] more, then stops — a swipe from Recents kills the process after
 * onStop, and the next start needs to know how long it lived on. Nothing is written before [begin]: the
 * file still holds the previous run, which the start-up detection reads first. The foreground comes from
 * [ActivityTracker]; a session that has not been in the foreground counts as background and does not beat.
 * It runs whether FeedbackKit is enabled or not: the next start needs the state either way.
 * Main thread, except [stop], which may come from any thread; [write] runs on [io], one write after
 * another.
 */
internal class SessionTracker(
    private val startedAt: Long,
    private val write: (SessionState) -> Unit,
    private val io: (Runnable) -> Unit,
    private val logger: SdkLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val heartbeatMillis: Long = HEARTBEAT_MILLIS,
    /** Whether the app is in the foreground when the tracker is made; [onForegroundChanged] follows it from there. */
    foreground: Boolean = false,
) : ActivityTracker.Listener {

    private val handler = Handler(Looper.getMainLooper())

    // Volatile: stop() can run on another thread (resetForTests on the instrumentation thread).
    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var stopped = false
    private var foreground = foreground
    private var lastForegroundAt = 0L
    private var lastBackgroundAt = 0L
    private var lastAliveAt = 0L

    // Beats still due in the background since the app left the foreground: counted, not timed by the wall clock.
    private var tailBeats = 0

    // In an initializer the constructor parameter `foreground` shadows the property: the beat reads the property.
    private val beat = object : Runnable {
        override fun run() {
            // A beat already running when stop() came from another thread goes no further.
            if (stopped) return
            val inForeground = this@SessionTracker.foreground
            if (!inForeground) tailBeats--
            try {
                val now = clock()
                lastAliveAt = now
                if (inForeground) lastForegroundAt = now
                persist()
            } catch (e: Exception) {
                logger.w("Session heartbeat failed", e)
            }
            if (!stopped && (inForeground || tailBeats > 0)) handler.postDelayed(this, heartbeatMillis)
        }
    }

    /** The previous run has been read: from now on this session is written, the first time right away. */
    fun begin(sessionId: String) {
        if (stopped || this.sessionId != null) return
        this.sessionId = sessionId
        if (foreground) startBeating() else persist()
    }

    /** No more writes: the runtime is torn down. Any thread. */
    fun stop() {
        stopped = true
        sessionId = null
        handler.removeCallbacks(beat)
    }

    override fun onForegroundChanged(foreground: Boolean) {
        this.foreground = foreground
        if (stopped || sessionId == null) return
        if (foreground) {
            startBeating()
        } else {
            handler.removeCallbacks(beat)
            val now = clock()
            lastForegroundAt = now
            lastBackgroundAt = now
            lastAliveAt = now
            persist()
            tailBeats = (BACKGROUND_TAIL_MILLIS / heartbeatMillis).toInt()
            if (tailBeats > 0) handler.postDelayed(beat, heartbeatMillis)
        }
    }

    private fun startBeating() {
        handler.removeCallbacks(beat)
        beat.run()
    }

    private fun persist() {
        val id = sessionId ?: return
        val state = SessionState(id, startedAt, lastForegroundAt, wentBackground = !foreground, lastBackgroundAt = lastBackgroundAt, lastAliveAt = lastAliveAt)
        io(
            Runnable {
                try {
                    write(state)
                } catch (e: Exception) {
                    logger.w("Could not write the session state", e)
                }
            },
        )
    }

    companion object {
        const val HEARTBEAT_MILLIS: Long = 2_000

        /** How long the heartbeat goes on after leaving the foreground (spec §8): the whole window a death still counts as near it. */
        const val BACKGROUND_TAIL_MILLIS: Long = ProactiveDetector.NEAR_FOREGROUND_MILLIS
    }
}

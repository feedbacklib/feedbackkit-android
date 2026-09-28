package io.github.feedbacklib.android.internal.proactive

import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import java.time.Instant

/**
 * An event for this process's prompt (spec §8): a crash, the `pending` of `proactive.json`, or a force restart,
 * which is never pending and lives only in the process that found it.
 */
internal data class ProactiveEvent(
    val info: ProactiveInfo,
    /** When the last prompt showed, from `proactive.json`; null if never. */
    val lastModalAt: Long?,
    /**
     * [PendingEvent.detectedAt]: which pending event a resolution clears. A force restart is found by this
     * process only, so here it is this process's start.
     */
    val detectedAt: Long,
)

/**
 * Proactive reporting's part of the runtime (spec §8), made in the main process only. Created in build()
 * on the main thread, where it only puts [CrashHandler] in front of the current handler: its marker
 * writer needs nothing the startup pass makes. [detect] reads what the previous run left, in the
 * background; only then [newSession] makes this run's session, which begins writing its own state.
 * The crash handler and the heartbeat work whether FeedbackKit is enabled or not; only the prompt
 * depends on that.
 *
 * Every [store] read and write — [detect], [resolve] and the heartbeat's writes through [io] — runs on
 * the one serial `sessionIo`. The only exception is `crash.marker`, which [CrashHandler] writes when none
 * is there, and deletes when the process survives the crash, on the crashing thread. A concurrent read
 * would lose a write: on API 30+ `AtomicFile.openRead()` deletes a pending `<name>.new`.
 */
internal class ProactiveReporting(
    private val store: SessionStore,
    private val exits: ExitReasons,
    private val logger: SdkLogger,
    /** Wall clock of build(): this session's start, and the moment detection compares with. */
    private val startedAt: Long,
    private val io: (Runnable) -> Unit,
) {
    private val crashHandler: CrashHandler = CrashHandler.install({ marker -> store.writeCrashIfAbsent(marker) }, { store.deleteCrash() })

    // Guarded by this: newSession() runs on the main thread, stop() on any.
    private var session: SessionTracker? = null
    private var stopped = false

    /**
     * The event for this process's prompt, or null (spec §8): one found now in what the previous run left,
     * else a crash an earlier start found and no prompt has resolved yet. A new crash and the exits it was
     * read from go into `proactive.json` first; only then is `crash.marker` deleted, so a process that dies
     * in between keeps the marker for the next start — unless the marker is older than the 72 h a pending
     * event lives, so a write that always fails cannot bring it back on every start. Only a marker that was
     * there when it was read is deleted: one that appears later is a crash of this run. The feature on or
     * off, the marker is consumed. A force restart is never pending: only its exit is recorded as processed,
     * and a crash waiting from an earlier start stays. Disk I/O — on `sessionIo` only. Each look is logged
     * at INFO for the device table.
     */
    fun detect(): ProactiveEvent? {
        val previous = store.readSession()
        val crashFile = store.readCrashFile()
        val crash = crashFile.marker
        val state = store.readProactive() ?: ProactiveState()
        val history = exits.history()
        val detection = ProactiveDetector.detect(previous, crash, history, state.lastProcessedExitAt, startedAt)
        val detected = detection?.let { PendingEvent(startedAt, it.trigger, it.exception, it.stacktrace) }
        val restart = detected?.takeIf { it.trigger == ProactiveTrigger.FORCE_RESTART }
        val next = state.copy(
            lastProcessedExitAt = ProactiveDetector.processedUpTo(state.lastProcessedExitAt, history, startedAt),
            pending = ProactiveDetector.nextPending(state.pending, detected.takeIf { restart == null }, startedAt),
        )
        val kept = next == state || store.writeProactive(next)
        val expired = crash != null && startedAt - crash.time > ProactiveDetector.PENDING_TTL_MILLIS
        if (crashFile.present && (kept || expired)) store.deleteCrash()
        if (!kept) {
            val marker = when {
                !crashFile.present -> "proactive.json is as it was"
                expired -> "the crash marker, older than 72 h, is deleted"
                else -> "the crash marker stays for the next start"
            }
            logger.w("The detected event could not be kept; $marker")
        }
        logger.i(describe(previous, crash, history, state, detection, next.pending))
        val event = restart ?: next.pending ?: return null
        return ProactiveEvent(event.toInfo(), state.lastModalAt, event.detectedAt)
    }

    /**
     * This run's session, made once [detect] has read the previous one; it writes nothing before
     * [SessionTracker.begin]. [foreground]: whether the app is in the foreground now — the tracker
     * hears only the changes after. Null once stopped or once made. Main thread.
     */
    @Synchronized
    fun newSession(foreground: Boolean): SessionTracker? {
        if (stopped || session != null) return null
        return SessionTracker(startedAt, { store.writeSession(it) }, io, logger, foreground = foreground).also { session = it }
    }

    /**
     * The prompt for [event] is settled (spec §8): it showed at [shownAt], or it was dropped (null) — inside
     * the gap or with the feature off. Either clears `pending`, if it still is that event; a shown prompt is
     * also the new time of the last prompt, which every outcome counts from. Disk I/O — on `sessionIo` only.
     */
    fun resolve(event: ProactiveEvent, shownAt: Long?) {
        val state = store.readProactive() ?: ProactiveState()
        val next = state.copy(
            lastModalAt = shownAt ?: state.lastModalAt,
            pending = state.pending?.takeUnless { it.detectedAt == event.detectedAt },
        )
        if (next != state) store.writeProactive(next)
    }

    /** The runtime is torn down: no more heartbeats, and the previous crash handler is back. Any thread. */
    fun stop() {
        val current = synchronized(this) {
            stopped = true
            session
        }
        current?.stop()
        CrashHandler.uninstall(crashHandler)
    }

    private fun describe(
        previous: SessionState?,
        crash: CrashMarker?,
        history: ExitHistory,
        state: ProactiveState,
        detection: Detection?,
        pending: PendingEvent?,
    ): String {
        val run = previous?.let {
            "startedAt=${it.startedAt}, wentBackground=${it.wentBackground}, sinceBackground=${since(it.lastBackgroundAt)}, " +
                "sinceAlive=${since(ProactiveDetector.lastAliveOf(it))}"
        } ?: "none"
        val latest = history.latest
        val exit = when {
            !history.supported -> "unavailable"
            latest == null -> "none"
            else -> "${latest.reason} at ${latest.timestamp}"
        }
        val processed = state.lastProcessedExitAt?.toString() ?: "nothing"
        return "Previous run: $run; crash marker=${crash != null}; exit=$exit, processed up to $processed; " +
            "detected ${detection?.trigger ?: "nothing"}; pending ${pending?.trigger ?: "nothing"}"
    }

    // A time never written (0) is no distance from this start.
    private fun since(at: Long): String = if (at <= 0) "never" else "${startedAt - at} ms"
}

/** The report's `proactive` block for [this] event: its detection time as ISO-8601 UTC (D14). */
private fun PendingEvent.toInfo(): ProactiveInfo =
    ProactiveInfo(trigger, Instant.ofEpochMilli(detectedAt).toString(), exception, stacktrace)

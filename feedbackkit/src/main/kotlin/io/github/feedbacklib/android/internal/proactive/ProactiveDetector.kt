package io.github.feedbacklib.android.internal.proactive

import io.github.feedbacklib.android.internal.report.ProactiveTrigger

/** Why a process ended, as far as proactive reporting cares (spec §8); [label] names a crash in the report. */
internal enum class ExitReason(val label: String?) {
    CRASH("Crash"),
    CRASH_NATIVE("Native crash"),
    ANR("ANR"),
    USER_REQUESTED(null),
    OTHER(null),
    SIGNALED(null),
    UNLISTED(null),
}

internal data class ExitRecord(val reason: ExitReason, val timestamp: Long, val description: String?)

/** What ApplicationExitInfo says: [supported] from API 30; [records] the exits of this process the system still keeps. */
internal data class ExitHistory(val supported: Boolean, val records: List<ExitRecord>) {
    /** The newest exit: the previous process's, once the system has recorded it. */
    val latest: ExitRecord? get() = records.maxByOrNull { it.timestamp }

    companion object {
        val UNSUPPORTED: ExitHistory = ExitHistory(supported = false, records = emptyList())
    }
}

internal data class Detection(val trigger: ProactiveTrigger, val exception: String?, val stacktrace: String?)

/**
 * The heuristic of spec §8, a pure function of what the previous run left. A crash first: the crash
 * marker, or on API 30+ an ANR or crash exit not looked at yet — later than `lastProcessedExitAt`, or
 * without it than the previous run's start. Then a force restart: no crash, the previous process died
 * near the foreground — still in it, or at most 10 s after leaving it (a swipe from Recents comes after
 * onStop) — and this cold start is at most 5 s after that death. The death is the time of its exit record
 * on API 30+, with a reason a user kill gives; below API 30 its last heartbeat, so there the window grows
 * by one heartbeat interval.
 */
internal object ProactiveDetector {

    const val FORCE_RESTART_WINDOW_MILLIS: Long = 5_000

    /** A death this long after leaving the foreground still counts as near it (spec §8). */
    const val NEAR_FOREGROUND_MILLIS: Long = 10_000

    /**
     * A force restart gets its prompt only if this process's first host screen resumed at most this long after
     * its start (spec §8); later, the system started the process (a service, a job), not the user.
     */
    const val FORCE_RESTART_SCREEN_WINDOW_MILLIS: Long = 10_000

    /** A pending event older than this is dropped at detection (spec §8): 72 h. */
    const val PENDING_TTL_MILLIS: Long = 72 * 60 * 60 * 1000L

    private val CRASH_EXITS = setOf(ExitReason.CRASH, ExitReason.CRASH_NATIVE, ExitReason.ANR)
    private val RESTART_EXITS = setOf(ExitReason.USER_REQUESTED, ExitReason.OTHER, ExitReason.SIGNALED)

    fun detect(previous: SessionState?, crash: CrashMarker?, exits: ExitHistory, lastProcessedExitAt: Long?, startedAt: Long): Detection? {
        if (crash != null) return Detection(ProactiveTrigger.CRASH, crash.exception, crash.stacktrace)
        val fresh = freshExits(exits, exitBound(lastProcessedExitAt, previous, startedAt))
        // Any crash among them, the newest first: a process started in the background may have ended since.
        // The system's description has no bound of its own: it is cut like a marker before the intent and the report get it.
        fresh.firstOrNull { it.reason in CRASH_EXITS }?.let { exit ->
            return Detection(ProactiveTrigger.CRASH, exit.reason.label, exit.description?.let(::trimmedTrace))
        }
        if (previous == null) return null
        // When the previous process died: its exit on API 30+, if a user kill gives that reason; else its last sign of life.
        val diedAt = if (exits.supported) {
            fresh.firstOrNull()?.takeIf { it.reason in RESTART_EXITS }?.timestamp ?: return null
        } else {
            lastAliveOf(previous)
        }
        if (!diedNearForeground(previous, diedAt, exact = exits.supported)) return null
        // Below API 30 the last sign of life can be up to one heartbeat before the death itself.
        val window = if (exits.supported) FORCE_RESTART_WINDOW_MILLIS else FORCE_RESTART_WINDOW_MILLIS + SessionTracker.HEARTBEAT_MILLIS
        val sinceDeath = startedAt - diedAt
        // Negative: the clock moved back between the runs, and nothing can be told.
        if (sinceDeath < 0 || sinceDeath > window) return null
        return Detection(ProactiveTrigger.FORCE_RESTART, null, null)
    }

    /** The last sign of life; a file of the previous version has only lastForegroundAt. */
    fun lastAliveOf(previous: SessionState): Long = maxOf(previous.lastAliveAt, previous.lastForegroundAt)

    /**
     * What `lastProcessedExitAt` becomes once [exits] were looked at (spec §8): the newest of them, never
     * earlier than before. A bound later than this start (the clock moved back) is dropped.
     */
    fun processedUpTo(lastProcessedExitAt: Long?, exits: ExitHistory, startedAt: Long): Long? =
        listOfNotNull(usableBound(lastProcessedExitAt, startedAt), exits.latest?.timestamp).maxOrNull()

    /**
     * `pending` after a look (spec §8): a new event replaces the one waiting; one waiting more than 72 h is
     * dropped. One "in the future" (the clock moved back) still waits.
     */
    fun nextPending(waiting: PendingEvent?, detected: PendingEvent?, now: Long): PendingEvent? =
        detected ?: waiting?.takeUnless { now - it.detectedAt > PENDING_TTL_MILLIS }

    /**
     * Whether more than [gapMillis] passed since the last prompt (spec §8). None shown yet passes; a
     * last prompt "in the future" (the clock moved back) passes too, or the prompt would stay silent
     * until the clock caught up.
     */
    fun gapPassed(lastModalAt: Long?, now: Long, gapMillis: Long): Boolean {
        if (lastModalAt == null) return true
        val since = now - lastModalAt
        return since < 0 || since > gapMillis
    }

    /** A bound later than this start (the clock moved back) is no bound. */
    private fun usableBound(lastProcessedExitAt: Long?, startedAt: Long): Long? = lastProcessedExitAt?.takeIf { it <= startedAt }

    /** Exits up to [bound] were looked at by an earlier start. No bound — no earlier look, no previous run — no exit counts. */
    private fun exitBound(lastProcessedExitAt: Long?, previous: SessionState?, startedAt: Long): Long? =
        usableBound(lastProcessedExitAt, startedAt) ?: previous?.startedAt

    /** The exits later than [bound], newest first. */
    private fun freshExits(exits: ExitHistory, bound: Long?): List<ExitRecord> =
        if (bound == null) emptyList() else exits.records.filter { it.timestamp > bound }.sortedByDescending { it.timestamp }

    /**
     * Whether the previous process died in the foreground or at most 10 s after leaving it. A session never in
     * the foreground did not (D8). A file of the previous version has no lastBackgroundAt: there
     * lastForegroundAt is the moment of leaving. [exact]: [diedAt] is the death itself (an exit record on
     * API 30+), not its last beat.
     */
    private fun diedNearForeground(previous: SessionState, diedAt: Long, exact: Boolean): Boolean {
        if (previous.lastForegroundAt <= 0) return false
        if (!previous.wentBackground) return true
        val leftAt = if (previous.lastBackgroundAt > 0) previous.lastBackgroundAt else previous.lastForegroundAt
        val sinceLeaving = diedAt - leftAt
        // Below API 30 diedAt is the last beat; a beat at the tail's end means the process outlived the window.
        return sinceLeaving >= 0 && if (exact) sinceLeaving <= NEAR_FOREGROUND_MILLIS else sinceLeaving < NEAR_FOREGROUND_MILLIS
    }
}

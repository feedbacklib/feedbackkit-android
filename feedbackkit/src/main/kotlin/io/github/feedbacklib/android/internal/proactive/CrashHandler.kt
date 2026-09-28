package io.github.feedbacklib.android.internal.proactive

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Leaves `crash.marker` for the next start (spec §8), then lets the crash go on exactly as it would
 * have: the handler installed before this one gets the same thread and throwable, once. The marker
 * is written synchronously on the crashing thread — the process is about to die — and nothing that
 * throws, not even an Error, changes how the host crashes. One crash at a time leaves a marker: the
 * one that brings the process down. Without a previous handler the crash is printed as
 * `ThreadGroup.uncaughtException` does with no default handler, and nothing more is done to the process.
 *
 * A marker already on disk is the previous run's, not consumed yet by the start-up detection: it is
 * never overwritten, and this crash leaves none of its own. A crash the process survives is no crash of
 * the run: when the previous handler returns (a host handler that swallows it, or none at all — the
 * system's handler never returns), the marker this crash wrote, and only that one, is deleted on the same
 * thread, and the handler waits for the next crash.
 */
internal class CrashHandler(
    val previous: Thread.UncaughtExceptionHandler?,
    /** Writes the marker unless one is on disk; true when this crash's marker is there now. */
    private val writeMarker: (CrashMarker) -> Boolean,
    private val deleteMarker: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : Thread.UncaughtExceptionHandler {

    // Held by the crash whose marker is being written or is on disk, until the process survives it.
    private val done = AtomicBoolean(false)

    // Set by [uninstall]: from then on crashes are only passed on.
    @Volatile
    private var detached = false

    override fun uncaughtException(thread: Thread, error: Throwable) {
        val marked = !detached && done.compareAndSet(false, true)
        var wrote = false
        if (marked) {
            try {
                wrote = writeMarker(crashMarkerOf(error, clock()))
            } catch (_: Throwable) {
                // The crash goes on unchanged; a missing marker only costs one prompt.
            }
        }
        passOn(thread, error)
        // Still here: the process survived this crash, so its marker must not prompt the next start.
        if (marked) survived(wrote)
    }

    private fun passOn(thread: Thread, error: Throwable) {
        val next = previous
        if (next != null) {
            next.uncaughtException(thread, error)
        } else {
            // What ThreadGroup.uncaughtException does with no default handler: printed, the process is not killed.
            try {
                System.err.print("Exception in thread \"" + thread.name + "\" ")
                error.printStackTrace(System.err)
            } catch (_: Throwable) {
            }
        }
    }

    private fun survived(wrote: Boolean) {
        // Only the marker this crash wrote: one it found on disk is the previous run's, still unread.
        if (wrote) {
            try {
                deleteMarker()
            } catch (_: Throwable) {
                // A marker left behind costs one wrong prompt; the host's thread goes on unchanged.
            }
        }
        if (!detached) done.set(false)
    }

    companion object {
        const val MAX_STACK_LINES: Int = 50
        const val MAX_LINE_LENGTH: Int = 500

        /** Puts a handler in front of the current default one. build(), main thread: no I/O here. */
        fun install(writeMarker: (CrashMarker) -> Boolean, deleteMarker: () -> Unit): CrashHandler =
            CrashHandler(Thread.getDefaultUncaughtExceptionHandler(), writeMarker, deleteMarker).also(Thread::setDefaultUncaughtExceptionHandler)

        /**
         * Puts the previous handler back, unless someone has put theirs in front of [handler] meanwhile,
         * and detaches [handler] either way: left in a host's chain it only passes crashes on and never
         * writes a marker, so a later build() does not leave two handlers that each write one.
         */
        fun uninstall(handler: CrashHandler) {
            handler.detached = true
            if (Thread.getDefaultUncaughtExceptionHandler() === handler) Thread.setDefaultUncaughtExceptionHandler(handler.previous)
        }
    }
}

/** The marker of [error] (spec §8): its class and its stack trace as [trimmedTrace] keeps it. */
internal fun crashMarkerOf(error: Throwable, time: Long): CrashMarker =
    CrashMarker(time = time, exception = error.javaClass.name, stacktrace = trimmedTrace(error.stackTraceToString()))

/**
 * A stack trace, or the system's description of an exit, as far as it goes into the session files, the
 * prompt's intent and the report (spec §8): its first 50 lines, each cut to 500 characters.
 */
internal fun trimmedTrace(text: String): String = text.trimEnd().lineSequence()
    .take(CrashHandler.MAX_STACK_LINES)
    .map { it.take(CrashHandler.MAX_LINE_LENGTH) }
    .joinToString("\n")

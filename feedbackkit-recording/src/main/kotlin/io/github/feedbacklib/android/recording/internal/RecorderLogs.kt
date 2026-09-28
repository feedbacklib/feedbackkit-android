package io.github.feedbacklib.android.recording.internal

import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.spi.RecorderLog

internal fun RecorderLog.e(message: String, error: Throwable? = null) = safely(LogLevel.ERROR, message, error)

internal fun RecorderLog.w(message: String, error: Throwable? = null) = safely(LogLevel.WARNING, message, error)

internal fun RecorderLog.i(message: String, error: Throwable? = null) = safely(LogLevel.INFO, message, error)

internal fun RecorderLog.d(message: String, error: Throwable? = null) = safely(LogLevel.DEBUG, message, error)

/**
 * Runs [block] and logs instead of throwing: on the recording thread an uncaught exception would kill
 * the host's process (spec §10).
 */
internal inline fun RecorderLog.guard(what: String, block: () -> Unit) {
    try {
        block()
    } catch (error: Exception) {
        e("Could not $what", error)
    }
}

private fun RecorderLog.safely(level: LogLevel, message: String, error: Throwable?) {
    try {
        log(level, message, error)
    } catch (_: Throwable) {
        // Logging is best effort.
    }
}

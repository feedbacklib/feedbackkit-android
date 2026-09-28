package io.github.feedbacklib.android.internal.core

import android.util.Log
import io.github.feedbacklib.android.LogLevel

internal fun interface LogSink {
    fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

internal object AndroidLogSink : LogSink {
    override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.ERROR -> Log.e(tag, message, throwable)
            LogLevel.WARNING -> Log.w(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.VERBOSE -> Log.v(tag, message, throwable)
            LogLevel.NONE -> Unit
        }
    }
}

/**
 * The SDK's logger. Never throws: a broken sink must not take the host app down (spec §10).
 */
internal class SdkLogger(
    level: LogLevel = LogLevel.WARNING,
    private val sink: LogSink = AndroidLogSink,
) {
    @Volatile
    var level: LogLevel = level

    fun e(message: String, throwable: Throwable? = null) = write(LogLevel.ERROR, message, throwable)
    fun w(message: String, throwable: Throwable? = null) = write(LogLevel.WARNING, message, throwable)
    fun i(message: String, throwable: Throwable? = null) = write(LogLevel.INFO, message, throwable)
    fun d(message: String, throwable: Throwable? = null) = write(LogLevel.DEBUG, message, throwable)
    fun v(message: String, throwable: Throwable? = null) = write(LogLevel.VERBOSE, message, throwable)

    /** The same threshold as [e]…[v]; [LogLevel.NONE] writes nothing. */
    fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        if (level != LogLevel.NONE) write(level, message, throwable)
    }

    private fun write(messageLevel: LogLevel, message: String, throwable: Throwable?) {
        val current = level
        if (current == LogLevel.NONE || messageLevel.ordinal > current.ordinal) return
        try {
            sink.write(messageLevel, TAG, message, throwable)
        } catch (_: Throwable) {
            // Logging is best effort; there is nowhere safer to report a failing sink.
        }
    }

    companion object {
        const val TAG: String = "FeedbackKit"
    }
}

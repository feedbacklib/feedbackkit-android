package io.github.feedbacklib.android.recording

import android.util.Log
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.spi.RecorderLog

/** The recorder's log in device tests: logcat tag FeedbackKitTest, so a failure shows why. */
internal val testLog = RecorderLog { level, message, error ->
    val priority = when (level) {
        LogLevel.ERROR -> Log.ERROR
        LogLevel.WARNING -> Log.WARN
        LogLevel.INFO -> Log.INFO
        else -> Log.DEBUG
    }
    Log.println(priority, "FeedbackKitTest", message + (error?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
}

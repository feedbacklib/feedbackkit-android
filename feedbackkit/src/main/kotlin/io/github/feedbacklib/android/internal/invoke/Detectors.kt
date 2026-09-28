package io.github.feedbacklib.android.internal.invoke

import android.app.Activity

/** Runs while the app is in foreground and its invocation event is enabled. Main thread. */
internal interface ProcessDetector {
    fun start()
    fun stop()
}

/** Follows the resumed host activity while its invocation event is enabled. Main thread. */
internal interface ActivityDetector {
    fun attach(activity: Activity)
    fun detach(activity: Activity)
}

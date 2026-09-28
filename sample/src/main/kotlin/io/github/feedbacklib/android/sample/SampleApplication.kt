package io.github.feedbacklib.android.sample

import android.app.Application
import android.os.SystemClock
import android.util.Log
import io.github.feedbacklib.android.BugReporting
import io.github.feedbacklib.android.ColorTheme
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.InvocationEvent
import io.github.feedbacklib.android.LogLevel

class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Visible in the zip that the host's own submit handler ran (spec §9).
        FeedbackKit.onReportSubmitHandler { it.addTag("sample") }
        // Before build(): the previous run is looked at once, at build() (spec §8).
        BugReporting.setProactiveReportingConfigurations(SampleSettings.proactiveConfigs(this))
        val started = SystemClock.elapsedRealtimeNanos()
        FeedbackKit.Builder(this, "sample-cid")
            .setSdkDebugLogsLevel(LogLevel.DEBUG)
            .setInvocationEvents(InvocationEvent.SHAKE, InvocationEvent.FLOATING_BUTTON)
            .setColorTheme(ColorTheme.SYSTEM)
            .build()
        // Spec §10: build() on the main thread stays under 5 ms; detection and the queue run in the background.
        Log.i(TAG, "FeedbackKit build() took ${(SystemClock.elapsedRealtimeNanos() - started) / 1_000} µs")
    }

    private companion object {
        const val TAG = "FeedbackKitSample"
    }
}

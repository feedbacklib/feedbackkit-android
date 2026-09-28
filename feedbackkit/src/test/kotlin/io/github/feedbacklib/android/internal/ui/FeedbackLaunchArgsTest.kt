package io.github.feedbacklib.android.internal.ui

import android.app.Application
import android.content.Intent
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.invoke.InvocationSource
import io.github.feedbacklib.android.internal.invoke.LaunchRequest
import io.github.feedbacklib.android.internal.invoke.ResumeRequest
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FeedbackLaunchArgsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `every field survives the intent`() {
        val args = FeedbackLaunchArgs(File(app.cacheDir, "shot.png"), true, "com.example.Main", InvocationSource.SHAKE, ReportType.QUESTION, screenshotRequested = false)
        val intent = args.toIntent(app)
        assertEquals(FeedbackActivity::class.java.name, intent.component?.className)
        assertEquals(args, FeedbackLaunchArgs.fromIntent(intent))
    }

    @Test
    fun `the token of the automatic recording taken at invocation travels with the launch`() {
        val request = LaunchRequest(null, false, "com.example.Host", InvocationSource.SHAKE, null, autoRecordingToken = "clip-1")
        assertEquals("clip-1", FeedbackLaunchArgs.fromIntent(FeedbackActivity.intent(app, request)).autoRecordingToken)
        assertNull(FeedbackLaunchArgs.fromIntent(FeedbackActivity.intent(app, request.copy(autoRecordingToken = null))).autoRecordingToken)
    }

    @Test
    fun `the token is a launch extra, never carried over as saved state`() {
        val intent = Intent()
        FeedbackLaunchArgs.putResumeState(intent, mapOf(FeedbackLaunchArgs.EXTRA_AUTO_RECORDING_TOKEN to "stale"))
        assertFalse(intent.hasExtra(FeedbackLaunchArgs.EXTRA_AUTO_RECORDING_TOKEN))
    }

    @Test
    fun `an intent without the flag counts as a requested screenshot`() {
        assertEquals(true, FeedbackLaunchArgs.fromIntent(Intent()).screenshotRequested)
    }

    @Test
    fun `missing or unknown extras fall back to a manual launch without a type`() {
        assertEquals(FeedbackLaunchArgs(null, false, null, InvocationSource.MANUAL, null), FeedbackLaunchArgs.fromIntent(Intent()))

        val odd = Intent()
            .putExtra(FeedbackLaunchArgs.EXTRA_SOURCE, "TELEPATHY")
            .putExtra(FeedbackLaunchArgs.EXTRA_REPORT_TYPE, "FRUSTRATING_EXPERIENCE")
        assertEquals(InvocationSource.MANUAL, FeedbackLaunchArgs.fromIntent(odd).source)
        assertNull(FeedbackLaunchArgs.fromIntent(odd).reportType)
    }

    @Test
    fun `a reopening launch carries the capture outcome and puts the saved state beside it`() {
        val extra = File(app.cacheDir, "extra.png")
        val request = LaunchRequest(
            screenshot = null,
            screenshotSecure = false,
            currentScreen = "com.example.Main",
            source = InvocationSource.MANUAL,
            reportType = ReportType.BUG,
            screenshotRequested = false,
            resume = ResumeRequest(
                state = mapOf("feedbackkit.comment" to "Two screens", "feedbackkit.menuShown" to false, "feedbackkit.type" to null, "android.intent.extra.TEXT" to "not ours"),
                extraScreenshot = extra,
                extraScreenshotFailed = false,
            ),
        )
        val intent = FeedbackActivity.intent(app, request)

        assertEquals(ResumeArgs(extra, extraScreenshotFailed = false), FeedbackLaunchArgs.fromIntent(intent).resume)
        assertEquals("Two screens", intent.getStringExtra("feedbackkit.comment"))
        assertFalse(intent.getBooleanExtra("feedbackkit.menuShown", true))
        assertTrue(intent.hasExtra("feedbackkit.type"))
        assertFalse("only the report screen's own keys travel", intent.hasExtra("android.intent.extra.TEXT"))
    }

    @Test
    fun `saved state never overwrites the launch's own extras`() {
        val intent = FeedbackLaunchArgs(null, false, "com.example.Main", InvocationSource.MANUAL, ReportType.BUG).toIntent(app)
        FeedbackLaunchArgs.putResumeState(intent, mapOf(FeedbackLaunchArgs.EXTRA_CURRENT_SCREEN to "com.example.Other"))
        assertEquals("com.example.Main", intent.getStringExtra(FeedbackLaunchArgs.EXTRA_CURRENT_SCREEN))
    }

    @Test
    fun `an ordinary launch is not a reopening`() {
        assertNull(FeedbackLaunchArgs.fromIntent(Intent()).resume)
    }

    private fun Intent.throughParcel(): Intent {
        val parcel = Parcel.obtain()
        try {
            writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `a reopening intent survives being parcelled, null texts and flags included`() {
        fun reopening(extra: File?, failed: Boolean) = LaunchRequest(
            screenshot = null,
            screenshotSecure = false,
            currentScreen = "com.example.Main",
            source = InvocationSource.MANUAL,
            reportType = ReportType.QUESTION,
            screenshotRequested = false,
            resume = ResumeRequest(
                state = mapOf("feedbackkit.comment" to "Two screens", "feedbackkit.menuShown" to true, "feedbackkit.type" to null),
                extraScreenshot = extra,
                extraScreenshotFailed = failed,
            ),
        )
        listOf(reopening(File(app.cacheDir, "extra.png"), failed = false), reopening(null, failed = true)).forEach { request ->
            val sent = FeedbackActivity.intent(app, request)
            val received = sent.throughParcel()

            assertEquals(FeedbackLaunchArgs.fromIntent(sent), FeedbackLaunchArgs.fromIntent(received))
            assertEquals(ResumeArgs(request.resume!!.extraScreenshot, request.resume.extraScreenshotFailed), FeedbackLaunchArgs.fromIntent(received).resume)
            assertEquals("Two screens", received.getStringExtra("feedbackkit.comment"))
            assertTrue(received.getBooleanExtra("feedbackkit.menuShown", false))
            assertTrue(received.hasExtra("feedbackkit.type"))
            assertNull(received.getStringExtra("feedbackkit.type"))
        }
    }

    @Test
    fun `a reopening launch after a recording carries the video or its failure`() {
        val video = File(app.cacheDir, "feedbackkit/recording/manual-1.mp4")
        val request = LaunchRequest(
            null, false, "com.example.Host", InvocationSource.MANUAL, ReportType.BUG, screenshotRequested = false,
            resume = ResumeRequest(emptyMap(), null, extraScreenshotFailed = false, recording = video),
        )
        assertEquals(ResumeArgs(null, false, recording = video), FeedbackLaunchArgs.fromIntent(FeedbackActivity.intent(app, request)).resume)

        val lost = request.copy(resume = ResumeRequest(emptyMap(), null, extraScreenshotFailed = false, recordingFailed = true))
        assertEquals(ResumeArgs(null, false, recording = null, recordingFailed = true), FeedbackLaunchArgs.fromIntent(FeedbackActivity.intent(app, lost)).resume)
    }

    private val crash = ProactiveInfo(ProactiveTrigger.CRASH, "2026-09-28T10:00:00Z", "java.lang.IllegalStateException", "java.lang.IllegalStateException: boom\n\tat A.b(A.kt:1)")

    @Test
    fun `the proactive block travels with the launch and brings its own type`() {
        val request = LaunchRequest(null, false, "com.example.Host", InvocationSource.PROACTIVE, ReportType.FRUSTRATING_EXPERIENCE, screenshotRequested = false, proactive = crash)
        val args = FeedbackLaunchArgs.fromIntent(FeedbackActivity.intent(app, request))
        assertEquals(crash, args.proactive)
        assertEquals(ReportType.FRUSTRATING_EXPERIENCE, args.reportType)
        assertEquals(InvocationSource.PROACTIVE, args.source)
        assertFalse(args.screenshotRequested)
        val restart = crash.copy(trigger = ProactiveTrigger.FORCE_RESTART, exception = null, stacktrace = null)
        assertEquals(restart, FeedbackLaunchArgs.fromIntent(FeedbackActivity.intent(app, request.copy(proactive = restart))).proactive)
    }

    @Test
    fun `FRUSTRATING_EXPERIENCE needs the proactive block, and an unknown trigger is none`() {
        val odd = Intent()
            .putExtra(FeedbackLaunchArgs.EXTRA_REPORT_TYPE, "FRUSTRATING_EXPERIENCE")
            .putExtra(FeedbackLaunchArgs.EXTRA_PROACTIVE_TRIGGER, "METEOR")
        val args = FeedbackLaunchArgs.fromIntent(odd)
        assertNull(args.proactive)
        assertNull(args.reportType)
    }

    @Test
    fun `the proactive extras are launch extras, never carried over as saved state`() {
        val intent = Intent()
        FeedbackLaunchArgs.putResumeState(intent, mapOf(FeedbackLaunchArgs.EXTRA_PROACTIVE_TRIGGER to "CRASH", FeedbackLaunchArgs.EXTRA_PROACTIVE_EXCEPTION to "E"))
        assertFalse(intent.hasExtra(FeedbackLaunchArgs.EXTRA_PROACTIVE_TRIGGER))
        assertFalse(intent.hasExtra(FeedbackLaunchArgs.EXTRA_PROACTIVE_EXCEPTION))
    }

    @Test
    fun `a proactive launch carries no screenshot and no automatic recording, whatever the intent says`() {
        val request = LaunchRequest(File(app.cacheDir, "shot.png"), true, "com.example.Host", InvocationSource.PROACTIVE, ReportType.FRUSTRATING_EXPERIENCE, autoRecordingToken = "clip-1", proactive = crash)
        val args = FeedbackLaunchArgs.fromIntent(FeedbackActivity.intent(app, request))
        assertEquals(crash, args.proactive)
        assertNull(args.screenshot)
        assertFalse(args.screenshotSecure)
        assertFalse(args.screenshotRequested)
        assertNull(args.autoRecordingToken)
    }
}

package io.github.feedbacklib.android.internal.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.BugReporting
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.FeatureState
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.InvocationEvent
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.core.FeedbackKitRuntime
import io.github.feedbacklib.android.internal.invoke.CaptureOverlayView
import io.github.feedbacklib.android.internal.invoke.CaptureRequest
import io.github.feedbacklib.android.internal.invoke.CaptureRequestJson
import io.github.feedbacklib.android.internal.invoke.InvocationSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.File
import java.time.Duration

/** The extra screenshot round trip through the real runtime, coordinator and activities (spec §6). */
@RunWith(RobolectricTestRunner::class)
class ExtraScreenshotFlowTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dismissals = mutableListOf<Pair<DismissType, ReportType>>()
    private val drafts get() = File(app.filesDir, "feedbackkit/drafts")

    @Before
    fun setUp() {
        FeedbackKit.resetForTests()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        File(app.filesDir, "feedbackkit").deleteRecursively()
    }

    @After
    fun tearDown() {
        FeedbackKit.resetForTests()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun build() {
        FeedbackKit.Builder(app, "cid").setInvocationEvents(InvocationEvent.NONE).build()
        FeedbackKit.identifyUser("me@example.com", null)
        BugReporting.setOnDismissCallback { type, reportType -> dismissals += type to reportType }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in 5 s" }
            idle()
            Thread.sleep(10)
        }
    }

    private fun awaitSdkScope() = runBlocking {
        FeedbackKitRuntime.current!!.scope.coroutineContext[Job]!!.children.forEach { it.join() }
    }

    private fun coordinator() = FeedbackKitRuntime.current!!.invocation.coordinator

    private fun panel(activity: Activity): CaptureOverlayView? {
        val decor = activity.window.decorView as ViewGroup
        return (0 until decor.childCount).map { decor.getChildAt(it) }.filterIsInstance<CaptureOverlayView>().singleOrNull()
    }

    private fun viewModelOf(controller: ActivityController<FeedbackActivity>): ReportDraftViewModel =
        ViewModelProvider(controller.get())[ReportDraftViewModel::class.java]

    /** The report screen over [host], which pauses as it would on a device. */
    private fun openReport(host: ActivityController<Activity>, autoRecordingToken: String? = null): ActivityController<FeedbackActivity> {
        host.pause()
        val args = FeedbackLaunchArgs(null, false, "com.example.Host", InvocationSource.MANUAL, ReportType.BUG, screenshotRequested = false, autoRecordingToken = autoRecordingToken)
        return Robolectric.buildActivity(FeedbackActivity::class.java, args.toIntent(app)).setup()
    }

    /** Types a comment, taps "Add screenshot" and lets the screen close as the activity would. */
    private fun stepAside(screen: ActivityController<FeedbackActivity>): ReportDraftViewModel {
        val vm = viewModelOf(screen)
        vm.onCommentChanged("Two screens")
        vm.onAddExtraScreenshot()
        idle()
        assertTrue(screen.get().isFinishing)
        screen.pause().stop().destroy()
        return vm
    }

    @Test
    fun `add screenshot steps aside, the controls wait on the host and cancel reopens the same draft`() {
        build()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val vm = stepAside(openReport(host))
        assertEquals(listOf(DismissType.ADD_ATTACHMENT to ReportType.BUG), dismissals)
        assertTrue(coordinator().isCapturing)
        assertTrue(coordinator().isUiOpen)
        waitUntil { File(drafts, "${vm.draftId}/capture.json").exists() }

        host.resume()
        val controls = panel(host.get())!!
        FeedbackKit.show()
        idle()
        assertNull("show() is ignored in capture mode", shadowOf(host.get()).nextStartedActivity)

        controls.findViewWithTag<View>(CaptureOverlayView.TAG_CANCEL).performClick()
        val reopening = shadowOf(host.get()).nextStartedActivity
        assertEquals(FeedbackActivity::class.java.name, reopening.component!!.className)
        assertNull(panel(host.get()))

        host.pause()
        val again = Robolectric.buildActivity(FeedbackActivity::class.java, reopening).setup()
        val back = viewModelOf(again)
        waitUntil { !File(drafts, "${vm.draftId}/capture.json").exists() }
        assertEquals(vm.draftId, back.draftId)
        assertEquals("Two screens", back.uiState.fields.comment)
        assertEquals("reopening reports nothing", 1, dismissals.size)
        assertFalse(coordinator().isCapturing)
    }

    /**
     * The started activity after a tap on Capture: 2 s of fake time cover the frame wait of the
     * hidden controls, then the PixelCopy result is saved in real time and posted back.
     */
    private fun awaitReopen(host: ActivityController<Activity>): Intent {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        var started: Intent? = null
        waitUntil {
            started = shadowOf(host.get()).nextStartedActivity
            started != null
        }
        return started!!
    }

    @Test
    fun `capture takes the host screenshot and the reopened report carries it`() {
        build()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val vm = stepAside(openReport(host))
        host.resume()

        panel(host.get())!!.findViewWithTag<View>(CaptureOverlayView.TAG_CAPTURE).performClick()
        val reopening = awaitReopen(host)
        assertNull(panel(host.get()))

        host.pause()
        val again = Robolectric.buildActivity(FeedbackActivity::class.java, reopening).setup()
        waitUntil { viewModelOf(again).uiState.attachments.any { it.kind == AttachmentKind.EXTRA_SCREENSHOT } }
        val back = viewModelOf(again)
        assertEquals(vm.draftId, back.draftId)
        assertEquals("Two screens", back.uiState.fields.comment)
        assertNull(back.uiState.notice)
        assertTrue(File(drafts, vm.draftId).list()!!.any { it.startsWith("extra-") && it.endsWith(".png") })
        waitUntil { !File(drafts, "${vm.draftId}/capture.json").exists() }
        assertEquals("reopening reports nothing", 1, dismissals.size)
    }

    @Test
    fun `with bug reporting off an open report still steps aside for a screenshot`() {
        build()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val screen = openReport(host)
        BugReporting.setState(FeatureState.DISABLED)
        idle()
        assertNull("the open form stays", viewModelOf(screen).closed.value)

        val vm = stepAside(screen)
        assertEquals(DismissType.ADD_ATTACHMENT, vm.closed.value)
        assertEquals(listOf(DismissType.ADD_ATTACHMENT to ReportType.BUG), dismissals)
        assertTrue(coordinator().isCapturing)
        waitUntil { File(drafts, "${vm.draftId}/capture.json").exists() }
        assertTrue(File(drafts, vm.draftId).exists())
    }

    @Test
    fun `disable then enable at once keeps capture mode and the draft`() {
        build()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val vm = stepAside(openReport(host))
        host.resume()
        waitUntil { File(drafts, "${vm.draftId}/capture.json").exists() }

        FeedbackKit.disable()
        FeedbackKit.enable() // before the main thread applied the disable
        idle()
        awaitSdkScope()

        assertTrue(coordinator().isCapturing)
        assertNotNull(panel(host.get()))
        assertEquals(listOf(DismissType.ADD_ATTACHMENT to ReportType.BUG), dismissals)
        assertTrue(File(drafts, "${vm.draftId}/capture.json").exists())
    }

    @Test
    fun `disabling FeedbackKit in capture mode drops the draft and reports CANCEL`() {
        build()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val vm = stepAside(openReport(host))
        host.resume()

        FeedbackKit.disable()
        idle()
        assertFalse(coordinator().isCapturing)
        assertNull(panel(host.get()))
        assertEquals(listOf(DismissType.ADD_ATTACHMENT to ReportType.BUG, DismissType.CANCEL to ReportType.BUG), dismissals)
        waitUntil { !File(drafts, vm.draftId).exists() }
    }

    @Test
    fun `disabling FeedbackKit in capture mode also gives up the automatic recording the report awaited`() {
        build()
        val clips = FeedbackKitRuntime.current!!.autoClips
        val token = clips.open()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        stepAside(openReport(host, autoRecordingToken = token))
        host.resume()

        FeedbackKit.disable()
        idle()
        val clip = File(app.cacheDir, "feedbackkit/recording/auto-late.mp4").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        assertFalse("nobody awaits it any more", clips.deliver(token, clip))
        waitUntil { !clip.exists() }
    }

    @Test
    fun `a host that calls show and throws from the ADD_ATTACHMENT callback opens no second report`() {
        build()
        BugReporting.setOnDismissCallback { type, reportType ->
            dismissals += type to reportType
            FeedbackKit.show()
            error("host bug")
        }
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        stepAside(openReport(host))
        host.resume()
        idle()

        assertEquals(listOf(DismissType.ADD_ATTACHMENT to ReportType.BUG), dismissals)
        assertTrue(coordinator().isCapturing)
        assertNull("show() is ignored in capture mode", shadowOf(host.get()).nextStartedActivity)
        assertNotNull(panel(host.get()))
    }

    @Test
    fun `an unreadable saved capture mode is dropped at start-up`() {
        File(drafts, "draft-broken").mkdirs()
        File(drafts, "draft-broken/capture.json").writeText("{ not json")

        build()
        awaitSdkScope()
        idle()

        assertFalse(coordinator().isCapturing)
        waitUntil { !File(drafts, "draft-broken/capture.json").exists() }
    }

    @Test
    fun `capture mode left by a killed process comes back on the first host screen`() {
        val saved = CaptureRequest(
            draftId = "draft-restore",
            reportType = ReportType.FEEDBACK,
            state = mapOf(
                "feedbackkit.draftId" to "draft-restore",
                "feedbackkit.step" to "FORM",
                "feedbackkit.type" to "FEEDBACK",
                "feedbackkit.menuShown" to false,
                "feedbackkit.comment" to "Before the kill",
            ),
            currentScreen = "com.example.Host",
        )
        File(drafts, "draft-restore").mkdirs()
        File(drafts, "draft-restore/capture.json").writeText(CaptureRequestJson.encode(saved))

        build()
        awaitSdkScope()
        waitUntil { coordinator().isCapturing }

        val host = Robolectric.buildActivity(Activity::class.java).setup()
        panel(host.get())!!.findViewWithTag<View>(CaptureOverlayView.TAG_CANCEL).performClick()
        host.pause()
        val again = Robolectric.buildActivity(FeedbackActivity::class.java, shadowOf(host.get()).nextStartedActivity).setup()
        waitUntil { viewModelOf(again).uiState.fields.comment == "Before the kill" }
        assertEquals(ReportType.FEEDBACK, viewModelOf(again).uiState.type)
        assertTrue("no second ADD_ATTACHMENT in the new process", dismissals.isEmpty())
    }

    @Test
    fun `a report screen the system restored wins over a saved capture mode`() {
        File(drafts, "draft-old").mkdirs()
        File(drafts, "draft-old/capture.json").writeText(CaptureRequestJson.encode(CaptureRequest("draft-old", ReportType.BUG, emptyMap(), null)))

        build()
        // Created before the start-up scan reaches the main thread, as a restored activity would be.
        val restored = Robolectric.buildActivity(FeedbackActivity::class.java, FeedbackLaunchArgs(null, false, null, InvocationSource.MANUAL, ReportType.BUG).toIntent(app)).setup()
        awaitSdkScope()
        idle()

        assertFalse(coordinator().isCapturing)
        waitUntil { !File(drafts, "draft-old/capture.json").exists() }
        restored.get().finish()
    }

    @Test
    fun `a report screen the system restored after the saved capture mode came back still wins`() {
        File(drafts, "draft-late").mkdirs()
        File(drafts, "draft-late/capture.json").writeText(CaptureRequestJson.encode(CaptureRequest("draft-late", ReportType.BUG, emptyMap(), null)))

        build()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        awaitSdkScope()
        waitUntil { coordinator().isCapturing } // the restore reached the main thread first
        assertNotNull(panel(host.get()))

        host.pause()
        val restored = Robolectric.buildActivity(FeedbackActivity::class.java, FeedbackLaunchArgs(null, false, null, InvocationSource.MANUAL, ReportType.BUG).toIntent(app)).setup()
        idle()

        assertFalse(coordinator().isCapturing)
        assertTrue("the restored screen is the open report", coordinator().isUiOpen)
        assertNull(panel(host.get()))
        waitUntil { !File(drafts, "draft-late/capture.json").exists() }
        assertTrue(dismissals.isEmpty())
        restored.get().finish()
    }
}

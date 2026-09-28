package io.github.feedbacklib.android.internal.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.core.view.WindowCompat
import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.BugReporting
import io.github.feedbacklib.android.ColorTheme
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.FeatureState
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.core.FeedbackKitRuntime
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.invoke.InvocationHooks
import io.github.feedbacklib.android.internal.invoke.InvocationSource
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class FeedbackActivityTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var opens = 0
    private var closes = 0
    private val dismissals = mutableListOf<Pair<DismissType, ReportType>>()

    @Before
    fun setUp() {
        FeedbackKit.resetForTests()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        File(app.filesDir, "feedbackkit").deleteRecursively()
        FeedbackKit.Builder(app, "cid").build()
        FeedbackKit.identifyUser("me@example.com", null)
        BugReporting.setOnDismissCallback { type, reportType -> dismissals += type to reportType }
        InvocationHooks.onUiOpened = { opens++ }
        InvocationHooks.onUiClosed = { closes++ }
    }

    @After
    fun tearDown() {
        FeedbackKit.resetForTests()
        // Sending opens the test WorkManager's database; left open, its CloseGuard warns in a later test.
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun launch(
        screenshot: File? = null,
        reportType: ReportType? = ReportType.BUG,
        secure: Boolean = false,
    ): ActivityController<FeedbackActivity> =
        Robolectric.buildActivity(
            FeedbackActivity::class.java,
            FeedbackLaunchArgs(screenshot, secure, "com.example.Host", InvocationSource.MANUAL, reportType).toIntent(app),
        ).setup()

    private fun viewModelOf(controller: ActivityController<FeedbackActivity>): ReportDraftViewModel =
        ViewModelProvider(controller.get())[ReportDraftViewModel::class.java]

    /** The ViewModel moves files and the SDK queues reports on real background threads. */
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in 5 s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun awaitSdkScope() = runBlocking {
        FeedbackKitRuntime.current!!.scope.coroutineContext[Job]!!.children.forEach { it.join() }
    }

    @Test
    fun `closing reports once from onPause with the dismiss reason, and not again at onDestroy`() {
        val controller = launch()
        controller.get().finish()
        controller.pause()
        assertEquals(1, closes)
        assertEquals(listOf(DismissType.CANCEL to ReportType.BUG), dismissals)

        controller.stop().destroy()
        assertEquals(1, closes)
        assertEquals(1, dismissals.size)
    }

    @Test
    fun `the screen tells the coordinator it is open, also when the system recreates it`() {
        val controller = launch()
        assertEquals(1, opens)

        controller.recreate() // the same happens when it is restored after process death
        assertEquals(2, opens)
        assertEquals(0, closes)
    }

    @Test
    fun `rotation neither reports a close nor loses the draft`() {
        val shot = File(app.cacheDir, "feedbackkit/capture/rotate.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val controller = launch(screenshot = shot)
        waitUntil { !shot.exists() }
        val vm = viewModelOf(controller)
        vm.onCommentChanged("Half written")
        val draftDir = File(app.filesDir, "feedbackkit/drafts/${vm.draftId}")

        controller.recreate()
        awaitSdkScope()

        assertEquals("Half written", viewModelOf(controller).uiState.fields.comment)
        assertTrue(File(draftDir, "screenshot.png").exists())
        assertEquals(0, closes)
    }

    @Test
    fun `the invocation screenshot moves into the draft and leaves with it on cancel`() {
        val shot = File(app.cacheDir, "feedbackkit/capture/shot.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val controller = launch(screenshot = shot)
        waitUntil { !shot.exists() }
        val vm = viewModelOf(controller)
        val draftDir = File(app.filesDir, "feedbackkit/drafts/${vm.draftId}")
        assertTrue(File(draftDir, "screenshot.png").exists())

        vm.onCancelRequested()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
        awaitSdkScope()

        assertFalse(draftDir.exists())
        assertEquals(listOf(DismissType.CANCEL to ReportType.BUG), dismissals)
    }

    @Test
    fun `a screen finished from outside (CLEAR_TASK, finishAffinity) deletes its draft`() {
        val shot = File(app.cacheDir, "feedbackkit/capture/external.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val controller = launch(screenshot = shot)
        waitUntil { !shot.exists() }
        val vm = viewModelOf(controller)
        vm.onCommentChanged("Half written")
        val draftDir = File(app.filesDir, "feedbackkit/drafts/${vm.draftId}")
        assertTrue(draftDir.exists())

        controller.get().finish() // not through the ViewModel: the host or the system finished it
        controller.pause().stop().destroy()
        awaitSdkScope()

        assertFalse(draftDir.exists())
        assertEquals(listOf(DismissType.CANCEL to ReportType.BUG), dismissals)
    }

    @Test
    fun `FeedbackKit disable() closes the open screen with CANCEL and deletes its draft`() {
        val shot = File(app.cacheDir, "feedbackkit/capture/disabled.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val controller = launch(screenshot = shot)
        waitUntil { !shot.exists() }
        val vm = viewModelOf(controller)
        vm.onCommentChanged("Half written")
        val draftDir = File(app.filesDir, "feedbackkit/drafts/${vm.draftId}")

        FeedbackKit.disable()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
        awaitSdkScope()

        assertFalse(draftDir.exists())
        assertEquals(listOf(DismissType.CANCEL to ReportType.BUG), dismissals)
    }

    @Test
    fun `BugReporting setState(DISABLED) leaves the open screen alone`() {
        val controller = launch()
        BugReporting.setState(FeatureState.DISABLED)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        assertEquals(0, closes)
    }

    @Test
    fun `sending queues the report, thanks the user and closes with SUBMIT`() {
        val controller = launch(reportType = ReportType.QUESTION)
        val vm = viewModelOf(controller)
        vm.onCommentChanged("Where are my settings?")
        vm.onPrimaryAction()
        waitUntil { vm.uiState.step == ReportStep.SUCCESS }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertTrue(controller.get().isFinishing)
        controller.pause()

        assertEquals(listOf(DismissType.SUBMIT to ReportType.QUESTION), dismissals)
        assertEquals(1, FeedbackKitRuntime.current!!.store.pending().size)
    }

    @Test
    fun `rotated while thanking the user it closes once, from the new screen, with SUBMIT`() {
        val controller = launch(reportType = ReportType.FEEDBACK)
        val vm = viewModelOf(controller)
        vm.onCommentChanged("Nice app")
        vm.onPrimaryAction()
        waitUntil { vm.uiState.step == ReportStep.SUCCESS }

        controller.recreate()
        assertEquals(0, closes)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()

        assertEquals(1, closes)
        assertEquals(listOf(DismissType.SUBMIT to ReportType.FEEDBACK), dismissals)
    }

    @Test
    fun `a host dismiss callback that throws does not break closing`() {
        BugReporting.setOnDismissCallback { _, _ -> error("host bug") }
        val controller = launch()
        controller.get().finish()
        controller.pause().stop().destroy()
        assertEquals(1, closes)
    }

    @Test
    fun `the menu gets light status bar icons over the dimmed host, the form its theme's icons`() {
        FeedbackKit.setColorTheme(ColorTheme.LIGHT)
        // Each screen checked in its first composition: Robolectric reuses Compose's main-thread
        // frame clock across tests, so a later recomposition is not reliably driven here.
        val menu = launch(reportType = null)
        assertEquals(ReportStep.MENU, viewModelOf(menu).uiState.step)
        val menuBars = WindowCompat.getInsetsController(menu.get().window, menu.get().window.decorView)
        assertFalse("light icons on the scrim", menuBars.isAppearanceLightStatusBars)
        assertTrue("the sheet at the bottom follows the theme", menuBars.isAppearanceLightNavigationBars)

        val form = launch(reportType = ReportType.BUG)
        val formBars = WindowCompat.getInsetsController(form.get().window, form.get().window.decorView)
        assertTrue("dark icons on the light form", formBars.isAppearanceLightStatusBars)
        assertTrue(formBars.isAppearanceLightNavigationBars)
    }

    @Test
    fun `a withheld screenshot shows the placeholder`() {
        val controller = launch(screenshot = null, secure = true)
        assertTrue(viewModelOf(controller).uiState.screenshotUnavailable)
        assertFalse(controller.get().isFinishing)
    }

    @Test
    fun `opened before build it closes at once without crashing`() {
        FeedbackKit.resetForTests()
        InvocationHooks.onUiClosed = { closes++ }
        val controller = Robolectric.buildActivity(
            FeedbackActivity::class.java,
            FeedbackLaunchArgs(null, false, null, InvocationSource.MANUAL, null).toIntent(app),
        ).create()
        assertTrue(controller.get().isFinishing)
        controller.destroy()
        assertEquals(1, closes)
    }

    @Test
    fun `host files go into the sent report but never into the attachment strip`() {
        FeedbackKit.addFileAttachment("log".toByteArray(), "app.log")
        FeedbackKit.addFileAttachment("state".toByteArray(), "state.json")
        val shot = File(app.cacheDir, "feedbackkit/capture/host-files.png").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val controller = launch(screenshot = shot, reportType = ReportType.QUESTION)
        val vm = viewModelOf(controller)
        waitUntil { vm.uiState.attachments.isNotEmpty() }
        assertEquals(listOf(AttachmentKind.SCREENSHOT), vm.uiState.attachments.map { it.kind })

        vm.onCommentChanged("With logs")
        vm.onPrimaryAction()
        waitUntil { vm.uiState.step == ReportStep.SUCCESS }

        val stored = FeedbackKitRuntime.current!!.store.pending().single().toPrepared()
        assertEquals(listOf("screenshot.png", "app.log", "state.json"), stored.attachments.map { it.fileName })
    }

    @Test
    fun `add image opens the system photo picker and its answer reaches the draft`() {
        val controller = launch(reportType = ReportType.BUG)
        val vm = viewModelOf(controller)
        vm.onAddGalleryImage()
        shadowOf(Looper.getMainLooper()).idle()

        val request = shadowOf(controller.get()).nextStartedActivityForResult
        assertTrue(request.intent.action, request.intent.action == "android.provider.action.PICK_IMAGES" || request.intent.action == Intent.ACTION_OPEN_DOCUMENT)

        // No provider answers getType for this uri: the answer arrives, the image is refused and said so.
        shadowOf(controller.get()).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(Uri.parse("content://nowhere/1")))
        waitUntil { vm.uiState.notice != null }
        assertEquals(AttachNotice.IMAGE_UNSUPPORTED, vm.uiState.notice)
    }

    // Controller ruling D6: the collector that launches the picker must not crash the host on any
    // failure, only on ActivityNotFoundException specifically — a real device's Play services
    // backport, an OEM picker or a broken document picker can fail in other ways too.
    @Test
    fun `a picker launch that fails for a reason other than ActivityNotFoundException still gets a notice, not a crash`() {
        var unavailableCalls = 0
        runPickerLaunch(SdkLogger(LogLevel.NONE), { unavailableCalls++ }) { throw IllegalStateException("boom") }
        assertEquals(1, unavailableCalls)
    }

    @Test
    fun `a picker launch cancelled by the coroutine propagates instead of being swallowed`() {
        var unavailableCalls = 0
        try {
            runPickerLaunch(SdkLogger(LogLevel.NONE), { unavailableCalls++ }) { throw CancellationException() }
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected: cancellation must never be reported as "no picker"
        }
        assertEquals(0, unavailableCalls)
    }

    @Test
    fun `a double tap on add image opens only one picker`() {
        val controller = launch(reportType = ReportType.BUG)
        val vm = viewModelOf(controller)
        vm.onAddGalleryImage()
        shadowOf(Looper.getMainLooper()).idle()
        vm.onAddGalleryImage()
        shadowOf(Looper.getMainLooper()).idle()

        val shadowActivity = shadowOf(controller.get())
        assertNotNull("the first tap must open a picker", shadowActivity.nextStartedActivityForResult)
        assertNull("a second picker must not open while the first is still pending", shadowActivity.nextStartedActivityForResult)
    }

    @Test
    fun `finishing for real drops the thumbnail cache, but rotation does not`() {
        ThumbnailCache.clear()
        val key = ThumbnailKey("finish-test", 0, 0, 480)
        val controller = launch(reportType = ReportType.BUG)
        ThumbnailCache.put(key, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap())

        controller.recreate()
        assertNotNull("rotation must not drop cached thumbnails", ThumbnailCache.get(key))

        controller.get().finish()
        controller.pause().stop().destroy()
        assertNull("closing for real drops thumbnails: nothing needs them once the screen is gone", ThumbnailCache.get(key))
    }

    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_LOW is deprecated, but still delivered on API < 34
    @Test
    fun `a system memory warning drops the thumbnail cache without closing the screen`() {
        ThumbnailCache.clear()
        val key = ThumbnailKey("trim-test", 0, 0, 480)
        val controller = launch(reportType = ReportType.BUG)
        ThumbnailCache.put(key, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap())

        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertNull(ThumbnailCache.get(key))
        assertFalse(controller.get().isFinishing)
    }

    @Test
    fun `a proactive launch opens on the prompt over the dimmed host and not now closes it as a cancel`() {
        FeedbackKit.setColorTheme(ColorTheme.LIGHT)
        val crash = ProactiveInfo(ProactiveTrigger.CRASH, "2026-09-28T10:00:00Z", "java.lang.IllegalStateException", "boom")
        val controller = Robolectric.buildActivity(
            FeedbackActivity::class.java,
            FeedbackLaunchArgs(null, false, "com.example.Host", InvocationSource.PROACTIVE, ReportType.FRUSTRATING_EXPERIENCE, screenshotRequested = false, proactive = crash).toIntent(app),
        ).setup()
        val vm = viewModelOf(controller)
        assertEquals(ReportStep.PROACTIVE_PROMPT, vm.uiState.step)
        val bars = WindowCompat.getInsetsController(controller.get().window, controller.get().window.decorView)
        assertFalse("light status bar icons on the scrim", bars.isAppearanceLightStatusBars)
        assertFalse("and light navigation bar icons: the card is centred", bars.isAppearanceLightNavigationBars)

        vm.onProactiveDeclined()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing)
        controller.pause()
        assertEquals(listOf(DismissType.CANCEL to ReportType.FRUSTRATING_EXPERIENCE), dismissals)
    }
}

package io.github.feedbacklib.android.internal.proactive

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.feedbacklib.android.BugReporting
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.InvocationEvent
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.ProactiveReportingConfigs
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.core.FeedbackKitRuntime
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import io.github.feedbacklib.android.internal.ui.FeedbackActivity
import io.github.feedbacklib.android.internal.ui.FeedbackLaunchArgs
import io.github.feedbacklib.android.internal.ui.ReportDraftViewModel
import io.github.feedbacklib.android.internal.ui.ReportStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** From the files a crashed run left to the prompt's launch, through the real runtime (spec §8). */
@RunWith(RobolectricTestRunner::class)
class ProactiveFlowTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = SessionStore({ File(app.filesDir, "feedbackkit/session") }, SdkLogger(LogLevel.NONE))
    private val marker = CrashMarker(
        System.currentTimeMillis() - 10_000,
        "java.lang.IllegalStateException",
        "java.lang.IllegalStateException: boom\n\tat com.example.Host.save(Host.kt:12)",
    )

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

    private fun awaitSdk() = runBlocking {
        FeedbackKitRuntime.current!!.scope.coroutineContext[Job]!!.children.forEach { it.join() }
    }

    private fun build(enabled: Boolean = true) {
        BugReporting.setProactiveReportingConfigurations(ProactiveReportingConfigs.Builder().isEnabled(enabled).build())
        FeedbackKit.Builder(app, "cid").setInvocationEvents(InvocationEvent.NONE).build()
        awaitSdk()
        ShadowLooper.idleMainLooper() // the session begins and the event is offered
    }

    // A host screen up to onResume, where the delay starts: setup()'s visible() moves Robolectric's clock on by frames.
    private fun screen(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).create().start().postCreate(null).resume()

    @Test
    fun `a crash of the previous run opens the prompt two seconds after the first screen`() {
        store.writeCrash(marker)
        build()
        val host = screen()
        ShadowLooper.idleMainLooper(1_999, TimeUnit.MILLISECONDS)
        assertNull(shadowOf(host.get()).nextStartedActivity)
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)

        val intent = shadowOf(host.get()).nextStartedActivity
        assertEquals(FeedbackActivity::class.java.name, intent.component?.className)
        val args = FeedbackLaunchArgs.fromIntent(intent)
        assertEquals(ReportType.FRUSTRATING_EXPERIENCE, args.reportType)
        assertEquals(ProactiveTrigger.CRASH, args.proactive?.trigger)
        assertEquals(marker.exception, args.proactive?.exception)
        assertEquals(marker.stacktrace, args.proactive?.stacktrace)
        assertNull(args.screenshot)
        assertFalse(args.screenshotRequested)

        awaitSdk() // the time of the prompt is written
        assertNotNull("the time of the prompt is written", store.readProactive()?.lastModalAt)
        assertNull(store.readCrash())
        assertNull("the prompt cleared its event", store.readProactive()?.pending)
        assertTrue("nothing is queued before Send", FeedbackKitRuntime.current!!.store.pending().isEmpty())
    }

    @Test
    fun `Tell us and Send queue a frustrating experience report with the previous run's proactive block`() {
        store.writeCrash(marker)
        build()
        FeedbackKit.identifyUser("me@example.com", null)
        val host = screen()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        val intent = shadowOf(host.get()).nextStartedActivity
        assertEquals(FeedbackActivity::class.java.name, intent.component?.className)

        host.pause()
        val prompt = Robolectric.buildActivity(FeedbackActivity::class.java, intent).setup()
        val vm = ViewModelProvider(prompt.get())[ReportDraftViewModel::class.java]
        assertEquals(ReportStep.PROACTIVE_PROMPT, vm.uiState.step)
        vm.onProactiveAccepted()
        vm.onCommentChanged("It closed when I saved")
        vm.onPrimaryAction()
        waitUntil { FeedbackKitRuntime.current!!.store.pending().isNotEmpty() }

        val stored = FeedbackKitRuntime.current!!.store.pending().single()
        val report = Json.parseToJsonElement(File(stored.directory, "report.json").readText()).jsonObject
        assertEquals(JsonPrimitive("FRUSTRATING_EXPERIENCE"), report["type"])
        val proactive = report["proactive"] as JsonObject
        assertEquals(JsonPrimitive("CRASH"), proactive["trigger"])
        assertEquals(JsonPrimitive(FeedbackLaunchArgs.fromIntent(intent).proactive!!.detectedAt), proactive["detectedAt"])
        assertEquals(JsonPrimitive(marker.exception), proactive["exception"])
        assertEquals(JsonPrimitive(marker.stacktrace), proactive["stacktrace"])
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in 5 s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `with the feature off the marker is consumed, nothing opens, and switching it on later finds nothing old`() {
        store.writeCrash(marker)
        build(enabled = false)
        val host = screen()
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
        assertNull(shadowOf(host.get()).nextStartedActivity)
        assertNull(store.readCrash())
        awaitSdk() // the dropped event leaves proactive.json
        assertNull(store.readProactive()?.pending)

        FeedbackKit.resetForTests()
        build(enabled = true)
        host.pause().resume()
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
        assertNull(shadowOf(host.get()).nextStartedActivity)
    }

    @Test
    fun `a start that shows no screen keeps the event, and the next start's first screen asks`() {
        store.writeCrash(marker)
        build()
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS) // a background start: no host screen comes
        assertEquals(ProactiveTrigger.CRASH, store.readProactive()?.pending?.trigger)
        assertNull(store.readCrash())

        FeedbackKit.resetForTests()
        build()
        val host = screen()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        val intent = shadowOf(host.get()).nextStartedActivity
        assertEquals(FeedbackActivity::class.java.name, intent?.component?.className)
        assertEquals(marker.exception, FeedbackLaunchArgs.fromIntent(intent).proactive?.exception)
        awaitSdk()
        assertNull(store.readProactive()?.pending)
    }

    /** The previous run died in the foreground a second ago, killed by the user (API 30+ exit record). */
    private fun forceRestartLeft() {
        val now = System.currentTimeMillis()
        store.writeSession(SessionState("previous", now - 60_000, now - 2_000, wentBackground = false))
        val manager = app.getSystemService(ActivityManager::class.java)
        shadowOf(manager).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(Application.getProcessName())
                .setReason(ApplicationExitInfo.REASON_USER_REQUESTED)
                .setTimestamp(now - 1_000)
                .build(),
        )
    }

    @Test
    fun `a force restart opens the prompt when the user's first screen comes at once`() {
        forceRestartLeft()
        build()
        val host = screen()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        val intent = shadowOf(host.get()).nextStartedActivity
        assertEquals(FeedbackActivity::class.java.name, intent?.component?.className)
        assertEquals(ProactiveTrigger.FORCE_RESTART, FeedbackLaunchArgs.fromIntent(intent).proactive?.trigger)
        awaitSdk()
        assertNotNull(store.readProactive()?.lastModalAt)
        assertNull(store.readProactive()?.pending)
    }

    @Test
    fun `a background start that finds a force restart leaves nothing pending, and the next start asks nothing`() {
        forceRestartLeft()
        build()
        ShadowLooper.idleMainLooper(15, TimeUnit.SECONDS) // the system started the process: no host screen comes
        assertNull("never pending", store.readProactive()?.pending)
        assertNotNull("its exit is processed", store.readProactive()?.lastProcessedExitAt)

        FeedbackKit.resetForTests()
        build()
        val host = screen()
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
        assertNull(shadowOf(host.get()).nextStartedActivity)
    }

    @Test
    fun `a prompt shown within the last day keeps the next crash quiet`() {
        store.writeProactive(ProactiveState(System.currentTimeMillis() - 60_000))
        store.writeCrash(marker)
        build()
        val host = screen()
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
        assertNull(shadowOf(host.get()).nextStartedActivity)
    }
}

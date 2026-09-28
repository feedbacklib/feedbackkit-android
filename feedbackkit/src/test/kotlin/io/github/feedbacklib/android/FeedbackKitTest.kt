package io.github.feedbacklib.android

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.feedbacklib.android.internal.core.FeedbackKitRuntime
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.core.Settings
import io.github.feedbacklib.android.internal.proactive.CrashHandler
import io.github.feedbacklib.android.internal.proactive.CrashMarker
import io.github.feedbacklib.android.internal.proactive.SessionState
import io.github.feedbacklib.android.internal.proactive.SessionStore
import io.github.feedbacklib.android.internal.queue.ReportStore
import io.github.feedbacklib.android.internal.queue.UploadWorkerBinding
import io.github.feedbacklib.android.internal.queue.WorkManagerUploadScheduler
import io.github.feedbacklib.android.internal.report.ReportDraft
import io.github.feedbacklib.android.internal.report.samplePayload
import io.github.feedbacklib.android.spi.ScreenRecorderProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class FeedbackKitTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FeedbackKit.resetForTests()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() {
        FeedbackKit.resetForTests()
        // Leaves no open WorkManager database behind for CloseGuard to report in a later test.
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun runtime(): FeedbackKitRuntime = FeedbackKitRuntime.current!!

    @Test
    fun `blank cid is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { FeedbackKit.Builder(app, "  ") }
    }

    @Test
    fun `build enables the sdk and a disabled build does not`() {
        FeedbackKit.Builder(app, "cid-1").build()
        assertTrue(FeedbackKit.isEnabled)

        FeedbackKit.resetForTests()
        FeedbackKit.Builder(app, "cid-1").build(FeatureState.DISABLED)
        assertFalse(FeedbackKit.isEnabled)
        FeedbackKit.enable()
        assertTrue(FeedbackKit.isEnabled)
    }

    @Test
    fun `second build is ignored`() {
        FeedbackKit.Builder(app, "first").build()
        val first = runtime()
        FeedbackKit.Builder(app, "second").build()
        assertSame(first, runtime())
        assertEquals("first", runtime().cid)
    }

    @Test
    fun `calls before build never crash and setters survive until build`() {
        val handler = OnReportSubmitHandler { }
        FeedbackKit.enable()
        FeedbackKit.addFileAttachment(byteArrayOf(1), "a.bin")
        FeedbackKit.clearFileAttachments()
        FeedbackKit.identifyUser("user@example.com", "User")
        FeedbackKit.onReportSubmitHandler(handler)
        assertFalse(FeedbackKit.isEnabled)
        assertNull(FeedbackKitRuntime.current)

        FeedbackKit.Builder(app, "cid-1").build()

        assertEquals("user@example.com", runtime().config.value.userEmail)
        assertEquals("User", runtime().config.value.userName)
        assertSame(handler, runtime().submitHandler)
        assertTrue(runBlocking { runtime().appFiles.snapshot() }.isEmpty())
    }

    @Test
    fun `default sender saves reports locally and a custom sender replaces it`() {
        FeedbackKit.Builder(app, "cid-1").build()
        assertTrue(runtime().sender is LocalReportSender)

        FeedbackKit.resetForTests()
        val custom = object : ReportSender {
            override suspend fun send(report: PreparedReport): SendResult = SendResult.Success
        }
        FeedbackKit.Builder(app, "cid-1").setReportSender(custom).build()
        assertSame(custom, runtime().sender)
    }

    @Test
    fun `log level reaches the sdk logger`() {
        FeedbackKit.Builder(app, "cid-1").setSdkDebugLogsLevel(LogLevel.VERBOSE).build()
        assertEquals(LogLevel.VERBOSE, runtime().logger.level)
    }

    @Test
    fun `worker gets a runner only while the sdk is enabled`() {
        FeedbackKit.Builder(app, "cid-1").build()
        assertNotNull(UploadWorkerBinding.runnerProvider!!.invoke())
        FeedbackKit.disable()
        assertNull(UploadWorkerBinding.runnerProvider!!.invoke())
    }

    @Test
    fun `submitted draft runs the handler and ends up queued`() = runBlocking {
        var seen: ReportType? = null
        FeedbackKit.onReportSubmitHandler { seen = it.type }
        FeedbackKit.Builder(app, "cid-1").build()

        val id = runtime().submitter.submit(ReportDraft(ReportType.QUESTION, null, "How?"))

        assertNotNull(id)
        assertEquals(ReportType.QUESTION, seen)
        assertEquals(listOf(id), runtime().store.pending().map { it.id })
    }

    @Test
    fun `submitting outlives the caller that started it`() = runBlocking {
        FeedbackKit.onReportSubmitHandler { Thread.sleep(300) }
        FeedbackKit.Builder(app, "cid-1").build()

        val deferred = runtime().submitReport(ReportDraft(ReportType.QUESTION, null, "How?"))
        val caller = launch { deferred.await() }
        caller.cancel()

        val id = deferred.await()

        assertNotNull(id)
        assertEquals(listOf(id), runtime().store.pending().map { it.id })
    }

    @Test
    fun `enable before build is ignored`() {
        FeedbackKit.enable()
        FeedbackKit.Builder(app, "cid-1").build(FeatureState.DISABLED)
        assertFalse(FeedbackKit.isEnabled)
    }

    @Test
    fun `start-up removes interrupted writes`() {
        val tmp = File(app.filesDir, "feedbackkit/reports/1-x.tmp")
        File(tmp, "report.json").apply { parentFile?.mkdirs() }.writeText("{")

        FeedbackKit.Builder(app, "cid-1").build()
        awaitStartUp()

        assertFalse(tmp.exists())
    }

    @Test
    fun `start-up schedules delivery when reports are pending`() {
        val logger = SdkLogger(LogLevel.NONE)
        ReportStore(File(app.filesDir, "feedbackkit/reports"), logger).enqueue(samplePayload(), emptyList())

        FeedbackKit.Builder(app, "cid-1").build()
        awaitStartUp()

        val workInfos = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(WorkManagerUploadScheduler.UNIQUE_WORK_NAME)
            .get()
        assertTrue(workInfos.isNotEmpty())
    }

    @Test
    fun `start-up purges abandoned drafts and keeps fresh ones`() {
        val drafts = File(app.filesDir, "feedbackkit/drafts")
        val old = File(drafts, "old").apply { mkdirs() }
        val fresh = File(drafts, "fresh").apply { mkdirs() }
        old.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)

        FeedbackKit.Builder(app, "cid-1").build()
        awaitStartUp()

        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `start-up resolves the recording directory before it looks up the recorder`() {
        var resolvedAtLookup: Boolean? = null
        val lookup = {
            resolvedAtLookup = FeedbackKitRuntime.current?.invocation?.isRecordingDirResolved
            emptyList<ScreenRecorderProvider>().iterator()
        }

        FeedbackKitRuntime.create(app, "cid-1", Settings(), lookup)
        awaitStartUp()

        assertEquals(true, resolvedAtLookup)
    }

    @Test
    fun `start-up goes on past a recorder lookup that throws`() {
        val tmp = File(app.filesDir, "feedbackkit/reports/1-x.tmp")
        File(tmp, "report.json").apply { parentFile?.mkdirs() }.writeText("{")
        // An iterator failure Recorders does not skip: discover() itself throws a RuntimeException.
        val failingLookup = {
            object : Iterator<ScreenRecorderProvider> {
                override fun hasNext(): Boolean = throw IllegalStateException("lookup broke")
                override fun next(): ScreenRecorderProvider = error("never reached")
            }
        }

        FeedbackKitRuntime.create(app, "cid-1", Settings(), failingLookup)
        awaitStartUp()

        assertTrue(ShadowLog.getLogsForTag(SdkLogger.TAG).any { it.msg == "Screen recorder lookup failed" })
        assertTrue(runtime().recorders.discovered)
        assertNull(runtime().recorders.recorder)
        assertFalse("the report queue cleanup after the lookup still ran", tmp.exists())
    }

    @Test
    fun `failures inside facade calls never reach the host`() {
        // Regression guard: facade calls after the runtime scope is cancelled must not throw.
        FeedbackKit.Builder(app, "cid-1").build()
        runtime().scope.cancel()
        FeedbackKit.addFileAttachment(ByteArray(10), "a.bin")
        FeedbackKit.enable()
        FeedbackKit.identifyUser(null, null)
    }

    @Test
    fun `build puts the crash handler in front of the host's and reset puts the host's back`() {
        val host = Thread.UncaughtExceptionHandler { _, _ -> }
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(host)
        try {
            FeedbackKit.Builder(app, "cid-1").build()
            val installed = Thread.getDefaultUncaughtExceptionHandler() as CrashHandler
            assertSame(host, installed.previous)
            FeedbackKit.resetForTests()
            assertSame(host, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `start-up reads the previous run before this session writes its own`() {
        val store = SessionStore({ File(app.filesDir, "feedbackkit/session") }, SdkLogger(LogLevel.NONE))
        val now = System.currentTimeMillis()
        store.writeSession(SessionState("previous", now - 60_000, now - 1_000, wentBackground = false))
        store.writeCrash(CrashMarker(now - 1_000, "java.lang.IllegalStateException", "boom"))

        FeedbackKit.Builder(app, "cid-1").setSdkDebugLogsLevel(LogLevel.VERBOSE).build()
        awaitStartUp()
        ShadowLooper.idleMainLooper() // this session begins
        awaitStartUp() // and writes for the first time

        assertNull("the marker was consumed", store.readCrash())
        val session = store.readSession()!!
        assertNotEquals("previous", session.sessionId)
        assertTrue("no screen has shown yet", session.wentBackground)
        assertTrue(ShadowLog.getLogsForTag(SdkLogger.TAG).any { it.msg.startsWith("Previous run:") && it.msg.contains("detected CRASH") })
    }

    @Test
    fun `the session heartbeat runs with FeedbackKit disabled`() {
        val store = SessionStore({ File(app.filesDir, "feedbackkit/session") }, SdkLogger(LogLevel.NONE))
        FeedbackKit.Builder(app, "cid-1").build(FeatureState.DISABLED)
        awaitStartUp()
        ShadowLooper.idleMainLooper() // this session begins
        Robolectric.buildActivity(Activity::class.java).setup() // and comes to the foreground
        awaitStartUp()
        val first = store.readSession()!!
        assertFalse(first.wentBackground)

        File(app.filesDir, "feedbackkit/session/session.json").delete()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        awaitStartUp()
        assertEquals("the next beat wrote the same session again", first.sessionId, store.readSession()?.sessionId)
    }

    @Test
    fun `a screen that came up before the session began is written as the foreground`() {
        val store = SessionStore({ File(app.filesDir, "feedbackkit/session") }, SdkLogger(LogLevel.NONE))
        FeedbackKit.Builder(app, "cid-1").build()
        Robolectric.buildActivity(Activity::class.java).setup()
        awaitStartUp()
        ShadowLooper.idleMainLooper() // this session begins, with the screen already up
        awaitStartUp()
        assertFalse(store.readSession()!!.wentBackground)
    }

    @Test
    fun `a process other than the main one installs no crash handler and leaves the session files alone`() {
        val store = SessionStore({ File(app.filesDir, "feedbackkit/session") }, SdkLogger(LogLevel.NONE))
        val now = System.currentTimeMillis()
        val previous = SessionState("previous", now - 60_000, now - 1_000, wentBackground = false)
        store.writeSession(previous)
        store.writeCrash(CrashMarker(now - 1_000, "java.lang.IllegalStateException", "boom"))
        val host = Thread.UncaughtExceptionHandler { _, _ -> }
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(host)
        ShadowApplication.setProcessName("${app.packageName}:remote")
        try {
            FeedbackKit.Builder(app, "cid-1").build()
            assertSame(host, Thread.getDefaultUncaughtExceptionHandler())
            Robolectric.buildActivity(Activity::class.java).setup()
            awaitStartUp()
            ShadowLooper.idleMainLooper(4, TimeUnit.SECONDS)
            awaitStartUp()
            assertNotNull("the main process's marker is left for it", store.readCrash())
            assertEquals(previous, store.readSession())
        } finally {
            ShadowApplication.setProcessName(app.packageName)
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    private fun awaitStartUp() {
        runBlocking { runtime().scope.coroutineContext[Job]!!.children.forEach { it.join() } }
    }
}

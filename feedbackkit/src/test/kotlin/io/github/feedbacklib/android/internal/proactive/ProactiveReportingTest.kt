package io.github.feedbacklib.android.internal.proactive

import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.LogSink
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ProactiveReportingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val lines = mutableListOf<String>()
    private val logger = SdkLogger(LogLevel.VERBOSE, LogSink { level, _, message, _ -> lines += "$level $message" })
    private val dir: File by lazy { File(temp.root, "session") }
    private val store by lazy { SessionStore({ dir }, logger) }
    private var exits = ExitHistory.UNSUPPORTED

    /** Runs when detection asks for the exits: after it has read the marker, before it deletes one. */
    private var beforeExitsRead: () -> Unit = {}

    /** 2023-11-14T22:13:20Z. */
    private val startedAt = 1_700_000_000_000L
    private val original: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
    private var reporting: ProactiveReporting? = null

    private fun reporting(): ProactiveReporting =
        ProactiveReporting(store, { beforeExitsRead(); exits }, logger, startedAt, io = { it.run() }).also { reporting = it }

    @After
    fun tearDown() {
        reporting?.stop()
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    @Test
    fun `a crash marker becomes the event, timed at this start, and stays pending until resolved`() {
        val marker = CrashMarker(startedAt - 5_000, "java.lang.IllegalStateException", "java.lang.IllegalStateException: boom")
        store.writeCrash(marker)
        store.writeProactive(ProactiveState(startedAt - 3_600_000))
        val subject = reporting()
        val expected = ProactiveEvent(ProactiveInfo(ProactiveTrigger.CRASH, "2023-11-14T22:13:20Z", marker.exception, marker.stacktrace), startedAt - 3_600_000, startedAt)
        assertEquals(expected, subject.detect())
        assertNull(store.readCrash())
        assertEquals("no prompt yet: a second look finds it again", expected, subject.detect())
        subject.resolve(expected, shownAt = startedAt + 2_000)
        assertNull("resolved, it is gone", subject.detect())
    }

    @Test
    fun `a damaged marker is removed and detects nothing`() {
        dir.mkdirs()
        val damaged = File(dir, "crash.marker").apply { writeText("{\"time\":") }
        assertNull(reporting().detect())
        assertFalse(damaged.exists())
    }

    @Test
    fun `every look at the previous run is logged for the device matrix`() {
        store.writeSession(SessionState("previous", startedAt - 60_000, startedAt - 2_000, wentBackground = false))
        exits = ExitHistory(supported = true, records = listOf(ExitRecord(ExitReason.USER_REQUESTED, startedAt - 1_000, null)))
        val event = reporting().detect()
        assertEquals(ProactiveTrigger.FORCE_RESTART, event?.info?.trigger)
        assertNull(event?.lastModalAt)
        val line = lines.single { it.startsWith("INFO Previous run:") }
        listOf("wentBackground=false", "sinceBackground=never", "sinceAlive=2000 ms", "crash marker=false", "exit=USER_REQUESTED", "processed up to nothing", "detected FORCE_RESTART", "pending nothing").forEach {
            assertTrue(line, line.contains(it))
        }
    }

    @Test
    fun `a force restart is this run's event only - never pending, its exit processed, a waiting crash kept`() {
        val waiting = PendingEvent(startedAt - 3_600_000, ProactiveTrigger.CRASH, "java.lang.IllegalStateException", "boom")
        store.writeProactive(ProactiveState(lastModalAt = 5L, pending = waiting))
        store.writeSession(SessionState("previous", startedAt - 60_000, startedAt - 2_000, wentBackground = false))
        exits = ExitHistory(supported = true, records = listOf(ExitRecord(ExitReason.USER_REQUESTED, startedAt - 1_000, null)))
        val subject = reporting()
        assertEquals(event(ProactiveTrigger.FORCE_RESTART, 5L, startedAt), subject.detect())
        assertEquals(ProactiveState(lastModalAt = 5L, lastProcessedExitAt = startedAt - 1_000, pending = waiting), store.readProactive())
        assertEquals("a second look: the exit is processed, the crash waits", waiting.detectedAt, subject.detect()?.detectedAt)
    }

    private fun event(trigger: ProactiveTrigger, lastModalAt: Long?, detectedAt: Long) =
        ProactiveEvent(ProactiveInfo(trigger, "2023-11-14T22:13:20Z", null, null), lastModalAt, detectedAt)

    @Test
    fun `a shown prompt clears its event and keeps its time and the exits already processed`() {
        store.writeProactive(ProactiveState(lastModalAt = 5L, lastProcessedExitAt = 7L, pending = PendingEvent(startedAt, ProactiveTrigger.FORCE_RESTART)))
        reporting().resolve(event(ProactiveTrigger.FORCE_RESTART, 5L, startedAt), shownAt = 123L)
        assertEquals(ProactiveState(lastModalAt = 123L, lastProcessedExitAt = 7L), store.readProactive())
    }

    @Test
    fun `a dropped event is cleared and the last prompt's time stays`() {
        store.writeProactive(ProactiveState(lastModalAt = 5L, lastProcessedExitAt = 7L, pending = PendingEvent(startedAt, ProactiveTrigger.CRASH)))
        reporting().resolve(event(ProactiveTrigger.CRASH, 5L, startedAt), shownAt = null)
        assertEquals(ProactiveState(lastModalAt = 5L, lastProcessedExitAt = 7L), store.readProactive())
    }

    @Test
    fun `a resolution for another event leaves the waiting one`() {
        val waiting = PendingEvent(startedAt, ProactiveTrigger.CRASH, "java.lang.IllegalStateException", "boom")
        store.writeProactive(ProactiveState(pending = waiting))
        reporting().resolve(event(ProactiveTrigger.CRASH, null, startedAt - 1), shownAt = null)
        assertEquals(ProactiveState(pending = waiting), store.readProactive())
    }

    @Test
    fun `the event and the newest exit go into proactive json, and the marker is consumed`() {
        val marker = CrashMarker(startedAt - 5_000, "java.lang.IllegalStateException", "java.lang.IllegalStateException: boom")
        store.writeCrash(marker)
        exits = ExitHistory(supported = true, records = listOf(ExitRecord(ExitReason.CRASH, startedAt - 4_000, null)))
        reporting().detect()
        val pending = PendingEvent(startedAt, ProactiveTrigger.CRASH, marker.exception, marker.stacktrace)
        assertEquals(ProactiveState(lastProcessedExitAt = startedAt - 4_000, pending = pending), store.readProactive())
        assertNull(store.readCrash())
    }

    @Test
    fun `when the event cannot be written the marker stays for the next start`() {
        val marker = CrashMarker(startedAt - 5_000, "java.lang.IllegalStateException", "java.lang.IllegalStateException: boom")
        store.writeCrash(marker)
        // AtomicFile deletes an empty directory in its way; Files.move cannot replace a non-empty one.
        File(dir, "proactive.json/in-the-way").apply { parentFile!!.mkdirs(); writeText("") }
        assertEquals("this process still offers it", ProactiveTrigger.CRASH, reporting().detect()?.info?.trigger)
        assertEquals(marker, store.readCrash())
        assertTrue(lines.any { it.startsWith("WARNING") && it.contains("crash marker stays") })
    }

    @Test
    fun `a marker older than 72 h goes even when the event cannot be written`() {
        val old = CrashMarker(startedAt - ProactiveDetector.PENDING_TTL_MILLIS - 1, "java.lang.IllegalStateException", "java.lang.IllegalStateException: long ago")
        store.writeCrash(old)
        File(dir, "proactive.json/in-the-way").apply { parentFile!!.mkdirs(); writeText("") }
        assertEquals("this process still offers it", ProactiveTrigger.CRASH, reporting().detect()?.info?.trigger)
        assertNull("it cannot repeat on every start", store.readCrash())
    }

    @Test
    fun `a marker that appears during detection is this run's own crash, and it stays`() {
        val own = CrashMarker(startedAt + 1_000, "java.lang.IllegalStateException", "java.lang.IllegalStateException: this run")
        // Absent when detection reads it; written by a crash of this run before the delete step.
        beforeExitsRead = { store.writeCrash(own) }
        assertNull(reporting().detect())
        assertEquals(own, store.readCrash())
    }

    @Test
    fun `an event an earlier start left pending comes back, with the time it was detected`() {
        val waiting = PendingEvent(startedAt - 3_600_000, ProactiveTrigger.CRASH, "java.lang.IllegalStateException", "boom")
        store.writeProactive(ProactiveState(lastModalAt = startedAt - 90_000_000, pending = waiting))
        val expected = ProactiveEvent(ProactiveInfo(ProactiveTrigger.CRASH, "2023-11-14T21:13:20Z", "java.lang.IllegalStateException", "boom"), startedAt - 90_000_000, startedAt - 3_600_000)
        assertEquals(expected, reporting().detect())
        assertEquals(waiting, store.readProactive()?.pending)
    }

    @Test
    fun `a pending event older than 72 h is dropped at detection`() {
        store.writeProactive(ProactiveState(pending = PendingEvent(startedAt - ProactiveDetector.PENDING_TTL_MILLIS - 1, ProactiveTrigger.FORCE_RESTART)))
        assertNull(reporting().detect())
        assertEquals(ProactiveState(), store.readProactive())
    }

    @Test
    fun `an exit already processed is not detected again`() {
        store.writeSession(SessionState("previous", startedAt - 60_000, startedAt - 30_000, wentBackground = true))
        exits = ExitHistory(supported = true, records = listOf(ExitRecord(ExitReason.ANR, startedAt - 10_000, "Input dispatching timed out")))
        val subject = reporting()
        val first = subject.detect()!!
        assertEquals("ANR", first.info.exception)
        subject.resolve(first, shownAt = startedAt + 5_000)
        assertNull(subject.detect())
        assertEquals(startedAt - 10_000, store.readProactive()?.lastProcessedExitAt)
    }

    @Test
    fun `a crash after build leaves its marker and goes on to the handler that was there`() {
        val seen = mutableListOf<Throwable>()
        // Like the system's handler, it ends the process: it never returns.
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            seen += e
            throw ProcessKilled()
        }
        reporting()
        val error = IllegalStateException("after build")
        try {
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
        } catch (_: ProcessKilled) {
        }
        assertEquals(listOf<Throwable>(error), seen)
        assertEquals("java.lang.IllegalStateException", store.readCrash()?.exception)
    }

    @Test
    fun `an uncaught exception the process survives leaves no marker behind`() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        reporting()
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("survived"))
        assertNull(store.readCrash())
    }

    private val previousRun = CrashMarker(startedAt - 5_000, "java.lang.OutOfMemoryError", "java.lang.OutOfMemoryError: previous run")

    @Test
    fun `an exception survived before detection leaves the previous run's marker as it was`() {
        store.writeCrash(previousRun)
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        reporting()
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("survived"))
        assertEquals(previousRun, store.readCrash())
    }

    @Test
    fun `a fatal crash before detection keeps the previous run's marker for the next start`() {
        store.writeCrash(previousRun)
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> throw ProcessKilled() }
        reporting()
        try {
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("this run"))
        } catch (_: ProcessKilled) {
        }
        assertEquals(previousRun, store.readCrash())
    }

    private class ProcessKilled : RuntimeException()
}

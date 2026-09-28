package io.github.feedbacklib.android.internal.proactive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CrashHandlerTest {

    private val events = mutableListOf<String>()
    private val markers = mutableListOf<CrashMarker>()
    private val seen = mutableListOf<Pair<Thread, Throwable>>()
    private var deletes = 0

    /** `crash.marker` as the disk has it: written by the handler, gone after a delete. */
    private var onDisk: CrashMarker? = null

    private val previous = Thread.UncaughtExceptionHandler { thread, error ->
        events += "previous"
        seen += thread to error
    }

    /** As far as the handler can tell, the system's KillApplicationHandler: it never returns. */
    private class ProcessKilled : RuntimeException()

    private val killing = Thread.UncaughtExceptionHandler { thread, error ->
        seen += thread to error
        throw ProcessKilled()
    }

    private fun handler(
        write: (CrashMarker) -> Boolean = { events += "marker"; markers += it; onDisk = it; true },
        previous: Thread.UncaughtExceptionHandler? = this.previous,
    ) = CrashHandler(previous, write, { deletes++; onDisk = null }, clock = { 42L })

    private fun crashFatally(handler: Thread.UncaughtExceptionHandler, error: Throwable) {
        try {
            handler.uncaughtException(Thread.currentThread(), error)
        } catch (_: ProcessKilled) {
            // The process is gone here; the test only looks at what it left on disk.
        }
    }

    @Test
    fun `the marker is written first, then the previous handler gets the same crash`() {
        val error = IllegalStateException("boom")
        val thread = Thread("worker")
        handler().uncaughtException(thread, error)
        assertEquals(listOf("marker", "previous"), events)
        assertSame(thread, seen.single().first)
        assertSame(error, seen.single().second)
        val marker = markers.single()
        assertEquals(42L, marker.time)
        assertEquals("java.lang.IllegalStateException", marker.exception)
        assertTrue(marker.stacktrace.startsWith("java.lang.IllegalStateException: boom"), marker.stacktrace)
    }

    @Test
    fun `the stack trace keeps at most 50 lines`() {
        fun deep(n: Int): Nothing = if (n == 0) throw IllegalArgumentException("deep") else deep(n - 1)
        val error = try {
            deep(200)
        } catch (e: IllegalArgumentException) {
            RuntimeException("wrapper", e)
        }
        val lines = crashMarkerOf(error, 1L).stacktrace.lines()
        assertEquals(CrashHandler.MAX_STACK_LINES, lines.size)
        assertEquals("java.lang.RuntimeException: wrapper", lines.first())
    }

    @Test
    fun `causes are kept while they fit, and an overlong line is cut`() {
        val inner = IllegalStateException("inner").apply { stackTrace = arrayOf(StackTraceElement("com.example.Store", "write", "Store.kt", 7)) }
        val outer = RuntimeException("outer", inner).apply { stackTrace = arrayOf(StackTraceElement("com.example.Host", "save", "Host.kt", 12)) }
        val trace = crashMarkerOf(outer, 1L).stacktrace
        assertTrue(trace.contains("Caused by: java.lang.IllegalStateException: inner"), trace)
        assertTrue(trace.contains("at com.example.Store.write(Store.kt:7)"), trace)

        val long = crashMarkerOf(RuntimeException("x".repeat(10_000)), 1L).stacktrace.lines().first()
        assertEquals(CrashHandler.MAX_LINE_LENGTH, long.length)
    }

    @Test
    fun `a marker that cannot be written, even with an Error, leaves the crash as it was`() {
        listOf(IOException("disk full"), OutOfMemoryError("no room"), StackOverflowError()).forEach { failure ->
            seen.clear()
            val error = IllegalStateException("boom")
            handler(write = { throw failure }).uncaughtException(Thread.currentThread(), error)
            assertSame(error, seen.single().second, failure.toString())
        }
    }

    @Test
    fun `a marker that cannot be deleted, even with an Error, leaves the crash as it was`() {
        val error = IllegalStateException("boom")
        CrashHandler(previous, { markers += it; true }, { throw OutOfMemoryError("no room") }).uncaughtException(Thread.currentThread(), error)
        assertSame(error, seen.single().second)
    }

    @Test
    fun `a throwable whose stack trace cannot be printed still reaches the previous handler`() {
        val odd = object : RuntimeException("odd") {
            override fun toString(): String = error("broken toString")
        }
        handler().uncaughtException(Thread.currentThread(), odd)
        assertEquals(listOf("previous"), events)
        assertSame(odd, seen.single().second)
    }

    @Test
    fun `a crash the previous handler returns from leaves no marker, and a later fatal crash still writes one`() {
        val crashes = handler(previous = { thread, error ->
            seen += thread to error
            if (error is UnsupportedOperationException) throw ProcessKilled()
        })
        crashes.uncaughtException(Thread.currentThread(), IllegalStateException("survived"))
        assertNull(onDisk, "the process lived on: that was no crash of the run")
        crashFatally(crashes, UnsupportedOperationException("fatal"))
        assertEquals(listOf("java.lang.IllegalStateException", "java.lang.UnsupportedOperationException"), markers.map { it.exception })
        assertEquals("java.lang.UnsupportedOperationException", onDisk?.exception)
        assertEquals(1, deletes)
    }

    @Test
    fun `a crash that ends the process keeps its marker, and only that crash is marked`() {
        val crashes = handler(previous = killing)
        crashFatally(crashes, IllegalStateException("first"))
        crashFatally(crashes, IllegalArgumentException("second"))
        assertEquals("java.lang.IllegalStateException", markers.single().exception)
        assertEquals("java.lang.IllegalStateException", onDisk?.exception)
        assertEquals(0, deletes)
        assertEquals(2, seen.size)
    }

    @Test
    fun `two threads crashing at once each reach the previous handler once and one marker is written`() {
        val inWrite = CountDownLatch(1)
        val release = CountDownLatch(1)
        val crashes = handler(
            write = { inWrite.countDown(); release.await(); synchronized(markers) { markers.add(it) } },
            previous = Thread.UncaughtExceptionHandler { t, e -> synchronized(seen) { seen += t to e } },
        )
        val a = Thread { crashes.uncaughtException(Thread.currentThread(), IllegalStateException("a")) }.apply { start() }
        assertTrue(inWrite.await(5, TimeUnit.SECONDS), "the first crash never reached its marker")
        val b = Thread { crashes.uncaughtException(Thread.currentThread(), IllegalStateException("b")) }.apply { start() }
        b.join(5_000)
        assertFalse(b.isAlive, "the second crash waited for the first one's marker")
        release.countDown()
        a.join(5_000)
        assertFalse(a.isAlive, "the first crash never finished")
        assertEquals(2, seen.size)
        assertEquals(setOf(a, b), seen.map { it.first }.toSet())
        assertEquals(1, markers.size)
        assertTrue(markers.single().stacktrace.startsWith("java.lang.IllegalStateException: a"), markers.single().stacktrace)
    }

    @Test
    fun `without a previous handler the crash is printed as the JVM does, and the surviving process keeps no marker`() {
        val error = IllegalStateException("boom")
        val printed = ByteArrayOutputStream()
        val err = System.err
        System.setErr(PrintStream(printed, true, "UTF-8"))
        try {
            handler(previous = null).uncaughtException(Thread("worker"), error)
        } finally {
            System.setErr(err)
        }
        val text = printed.toString("UTF-8")
        assertTrue(text.startsWith("Exception in thread \"worker\" java.lang.IllegalStateException: boom"), text)
        assertEquals(1, markers.size)
        assertNull(onDisk, "nothing killed the process: that was no crash of the run")
        assertEquals(1, deletes)
    }

    @Test
    fun `install puts the handler in front of the current one and uninstall puts that back`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            val installed = CrashHandler.install({ markers.add(it) }, {})
            assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())
            assertSame(previous, installed.previous)
            CrashHandler.uninstall(installed)
            assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `after uninstall a handler still in a host's chain only passes the crash on`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            val installed = CrashHandler.install({ events += "marker"; markers.add(it) }, { deletes++ })
            // The host put its own in front and chains to ours: uninstall cannot take ours out of that chain.
            Thread.setDefaultUncaughtExceptionHandler { thread, error -> installed.uncaughtException(thread, error) }
            CrashHandler.uninstall(installed)
            val error = IllegalStateException("after stop")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertTrue(markers.isEmpty())
            assertEquals(0, deletes, "nothing written, so nothing deleted")
            assertEquals(listOf("previous"), events)
            assertSame(error, seen.single().second)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `uninstall leaves alone a handler the host put in front`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val installed = CrashHandler.install({ markers.add(it) }, {})
            val host = Thread.UncaughtExceptionHandler { _, _ -> }
            Thread.setDefaultUncaughtExceptionHandler(host)
            CrashHandler.uninstall(installed)
            assertSame(host, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `a host handler installed after ours and chaining to it runs first and the marker is still written`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            val installed = CrashHandler.install({ events += "marker"; markers.add(it) }, {})
            val host = Thread.UncaughtExceptionHandler { thread, error ->
                events += "host"
                installed.uncaughtException(thread, error)
            }
            Thread.setDefaultUncaughtExceptionHandler(host)
            val error = IllegalStateException("boom")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertEquals(listOf("host", "marker", "previous"), events)
            assertEquals(1, markers.size)
            assertSame(error, seen.single().second)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    /** The disk as SessionStore.writeCrashIfAbsent sees it: a marker already there is left as it is. */
    private fun writeIfAbsent(marker: CrashMarker): Boolean {
        if (onDisk != null) return false
        events += "marker"
        markers += marker
        onDisk = marker
        return true
    }

    private val previousRun = CrashMarker(1L, "java.lang.OutOfMemoryError", "java.lang.OutOfMemoryError: previous run")

    @Test
    fun `a survived exception leaves the previous run's unread marker as it was`() {
        onDisk = previousRun
        handler(write = ::writeIfAbsent).uncaughtException(Thread.currentThread(), IllegalStateException("survived"))
        assertSame(previousRun, onDisk)
        assertEquals(0, deletes, "only a marker this handler wrote is deleted")
        assertTrue(markers.isEmpty())
        assertEquals(listOf("previous"), events)
    }

    @Test
    fun `a fatal crash with the previous run's marker still on disk keeps that marker`() {
        onDisk = previousRun
        crashFatally(handler(write = ::writeIfAbsent, previous = killing), IllegalStateException("this run"))
        assertSame(previousRun, onDisk)
        assertEquals(0, deletes)
        assertEquals(1, seen.size)
    }

    @Test
    fun `once the previous marker is gone, the next crash of the run writes its own`() {
        onDisk = previousRun
        val crashes = handler(write = ::writeIfAbsent, previous = { thread, error ->
            seen += thread to error
            if (error is UnsupportedOperationException) throw ProcessKilled()
        })
        crashes.uncaughtException(Thread.currentThread(), IllegalStateException("survived"))
        onDisk = null // the start-up detection consumed the previous run's marker
        crashFatally(crashes, UnsupportedOperationException("fatal"))
        assertEquals("java.lang.UnsupportedOperationException", onDisk?.exception)
        assertEquals(0, deletes)
    }
}

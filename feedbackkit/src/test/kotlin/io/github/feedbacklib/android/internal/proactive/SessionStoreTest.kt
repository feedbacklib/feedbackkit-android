package io.github.feedbacklib.android.internal.proactive

import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.LogSink
import io.github.feedbacklib.android.internal.core.SdkLogger
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
class SessionStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val lines = mutableListOf<String>()
    private val logger = SdkLogger(LogLevel.VERBOSE, LogSink { level, _, message, _ -> lines += "$level $message" })
    private val dir: File by lazy { File(temp.root, "session") }
    private val store by lazy { SessionStore({ dir }, logger) }

    private val session = SessionState("s-1", 1_000, 2_000, wentBackground = false)
    private val crash = CrashMarker(3_000, "java.lang.IllegalStateException", "java.lang.IllegalStateException: boom")

    @Test
    fun `nothing written yet reads as nothing`() {
        assertNull(store.readSession())
        assertNull(store.readCrash())
        assertNull(store.readProactive())
        store.deleteCrash()
        assertTrue(lines.none { it.startsWith("WARNING") })
    }

    @Test
    fun `each file is written into the session directory and read back`() {
        assertTrue(store.writeSession(session))
        assertTrue(store.writeCrash(crash))
        assertTrue(store.writeProactive(ProactiveState(4_000)))
        assertEquals(setOf("session.json", "crash.marker", "proactive.json"), dir.list()!!.toSet())
        assertEquals(session, store.readSession())
        assertEquals(crash, store.readCrash())
        assertEquals(ProactiveState(4_000), store.readProactive())
    }

    @Test
    fun `a later write replaces the earlier one`() {
        store.writeSession(session)
        store.writeSession(session.copy(lastForegroundAt = 4_000, wentBackground = true))
        assertEquals(session.copy(lastForegroundAt = 4_000, wentBackground = true), store.readSession())
    }

    @Test
    fun `deleting the crash marker leaves the other files`() {
        store.writeSession(session)
        store.writeCrash(crash)
        store.deleteCrash()
        assertNull(store.readCrash())
        assertEquals(session, store.readSession())
        assertFalse(File(dir, "crash.marker").exists())
    }

    @Test
    fun `a damaged or oversized file reads as nothing and the next write replaces it`() {
        dir.mkdirs()
        File(dir, "session.json").writeText("{\"sessionId\":")
        File(dir, "proactive.json").writeBytes(ByteArray(SessionStore.MAX_FILE_BYTES + 1) { ' '.code.toByte() })
        assertNull(store.readSession())
        assertNull(store.readProactive())
        assertTrue(store.writeSession(session))
        assertEquals(session, store.readSession())
    }

    @Test
    fun `a file where the directory should be makes writes fail without throwing`() {
        dir.writeText("not a directory")
        assertFalse(store.writeSession(session))
        assertFalse(store.writeCrash(crash))
        assertNull(store.readSession())
        assertTrue(lines.any { it.startsWith("WARNING") && it.contains("session.json") })
    }

    @Test
    fun `a directory where a file should be reads as nothing`() {
        File(dir, "crash.marker").mkdirs()
        assertNull(store.readCrash())
        store.deleteCrash()
    }

    @Test
    fun `a corrupt session json reads as nothing and logs a warning`() {
        dir.mkdirs()
        File(dir, "session.json").writeText("{\"sessionId\":")
        assertNull(store.readSession())
        assertTrue(lines.any { it.startsWith("WARNING") && it.contains("session.json") })
    }

    @Test
    fun `reading the marker tells a damaged one from none`() {
        assertEquals(CrashFile(present = false, marker = null), store.readCrashFile())
        store.writeCrash(crash)
        assertEquals(CrashFile(present = true, marker = crash), store.readCrashFile())
        File(dir, "crash.marker").writeText("{\"time\":")
        assertEquals(CrashFile(present = true, marker = null), store.readCrashFile())
        File(dir, "crash.marker").writeBytes(ByteArray(SessionStore.MAX_FILE_BYTES + 1) { ' '.code.toByte() })
        assertEquals(CrashFile(present = true, marker = null), store.readCrashFile())
    }

    @Test
    fun `a marker already there is not overwritten, and without one the marker is written`() {
        assertTrue(store.writeCrashIfAbsent(crash))
        assertEquals(crash, store.readCrash())
        assertFalse(store.writeCrashIfAbsent(crash.copy(time = 9_000, exception = "java.lang.Error")))
        assertEquals(crash, store.readCrash())
        store.deleteCrash()
        assertTrue(store.writeCrashIfAbsent(crash.copy(time = 9_000)))
        assertEquals(9_000L, store.readCrash()?.time)
    }
}

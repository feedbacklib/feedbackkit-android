package io.github.feedbacklib.android.internal.recording

import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.LogSink
import io.github.feedbacklib.android.internal.core.SdkLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PendingClipsTest {

    private val deleted = mutableListOf<File>()
    private val clips = PendingClips({ deleted += it }, SdkLogger(LogLevel.NONE, LogSink { _, _, _, _ -> }))
    private val clip = File("auto-1.mp4")

    @Test
    fun `a clip delivered before anyone waits is taken once`() = runBlocking {
        val token = clips.open()
        assertTrue(clips.deliver(token, clip))
        assertEquals(clip, clips.await(token))
        assertNull(clips.await(token), "taken: the registry forgot it")
        assertTrue(deleted.isEmpty(), "a clip handed over is the consumer's")
    }

    @Test
    fun `a waiting consumer gets the clip when it comes`() = runBlocking {
        val token = clips.open()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { clips.await(token) }
        assertFalse(waiting.isCompleted)
        clips.deliver(token, clip)
        assertEquals(clip, waiting.await())
    }

    @Test
    fun `no clip is an answer too`() = runBlocking {
        val token = clips.open()
        clips.deliver(token, null)
        assertNull(clips.await(token))
    }

    @Test
    fun `an unknown token answers null at once`() = runBlocking {
        assertNull(clips.await("from-a-previous-process"))
        assertFalse(clips.deliver("from-a-previous-process", clip))
        assertEquals(listOf(clip), deleted, "nobody can take it")
    }

    @Test
    fun `a clip abandoned before it comes is deleted when it comes`() = runBlocking {
        val token = clips.open()
        clips.abandon(token)
        assertFalse(clips.deliver(token, clip))
        assertEquals(listOf(clip), deleted)
        assertNull(clips.await(token))
    }

    @Test
    fun `a clip abandoned after it came and before it was taken is deleted`() = runBlocking {
        val token = clips.open()
        clips.deliver(token, clip)
        clips.abandon(token)
        assertEquals(listOf(clip), deleted)
        assertNull(clips.await(token))
    }

    @Test
    fun `abandoning wakes a consumer that waits, with nothing`() = runBlocking {
        val token = clips.open()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { clips.await(token) }
        clips.abandon(token)
        yield()
        assertNull(waiting.await())
    }

    @Test
    fun `after the cap gave up on a clip, the late clip is deleted`() = runBlocking {
        val token = clips.open()
        assertTrue(clips.deliver(token, null), "the cap: no clip")
        assertFalse(clips.deliver(token, clip), "the clip came after all")
        assertEquals(listOf(clip), deleted)
        assertNull(clips.await(token))
    }

    @Test
    fun `a clip taken is never deleted by a later abandon`() = runBlocking {
        val token = clips.open()
        clips.deliver(token, clip)
        assertEquals(clip, clips.await(token))
        clips.abandon(token)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `a failing delete is logged, never thrown`() {
        val lines = mutableListOf<String>()
        val failing = PendingClips({ error("disk") }, SdkLogger(LogLevel.VERBOSE, LogSink { level, _, message, _ -> lines += "$level $message" }))
        assertFalse(failing.deliver("unknown", clip))
        assertTrue(lines.any { it.startsWith("WARNING") })
    }
}

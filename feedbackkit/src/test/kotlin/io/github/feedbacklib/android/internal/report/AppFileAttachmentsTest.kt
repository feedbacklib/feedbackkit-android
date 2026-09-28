package io.github.feedbacklib.android.internal.report

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class AppFileAttachmentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val staging = File(context.cacheDir, "staging")
    private val logger = SdkLogger(LogLevel.NONE)
    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var files: AppFileAttachments

    @Before
    fun setUp() {
        staging.deleteRecursively()
        files = AppFileAttachments(context.contentResolver, { staging }, logger, scope)
    }

    @After
    fun tearDown() = scope.cancel()

    // snapshot() runs on the same serial queue after everything queued before it.
    private fun snapshot() = runBlocking { files.snapshot() }

    private fun readAll(): List<ByteArray> = snapshot().map { it.open()!!.use { s -> s.readBytes() } }

    @Test
    fun `byte attachment is offered as an app file with a guessed mime type and the 5 MB cap`() {
        files.add("hello".toByteArray(), "log.txt")
        val attachment = snapshot().single()
        assertEquals(AttachmentKind.APP_FILE, attachment.meta.kind)
        assertEquals("log.txt", attachment.meta.fileName)
        assertEquals("text/plain", attachment.meta.mimeType)
        assertEquals(AppFileAttachments.MAX_FILE_BYTES.toLong(), attachment.maxBytes)
        assertArrayEquals("hello".toByteArray(), readAll().single())
    }

    @Test
    fun `fourth file pushes out the oldest and deletes its staged copy`() {
        (1..4).forEach { files.add(byteArrayOf(it.toByte()), "f$it.bin") }
        assertEquals(listOf("f2.bin", "f3.bin", "f4.bin"), snapshot().map { it.meta.fileName })
        assertEquals(3, staging.listFiles()!!.size)
    }

    @Test
    fun `byte array over 5 MB is rejected`() {
        files.add(ByteArray(AppFileAttachments.MAX_FILE_BYTES + 1), "big.bin")
        assertTrue(snapshot().isEmpty())
    }

    @Test
    fun `clear forgets every file and removes staged copies`() {
        files.add(byteArrayOf(1), "a.bin")
        files.clear()
        assertTrue(snapshot().isEmpty())
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a file added right after clear is kept while the one before it is gone`() {
        files.add(byteArrayOf(1), "before.bin")
        files.clear()
        files.add(byteArrayOf(2), "after.bin")
        assertEquals(listOf("after.bin"), snapshot().map { it.meta.fileName })
        assertArrayEquals(byteArrayOf(2), readAll().single())
        assertEquals("only the file added after clear stays staged", 1, staging.listFiles()!!.size)
    }

    @Test
    fun `uri attachment is read through the content resolver when the report is queued`() {
        val uri = Uri.parse("content://com.example.provider/logs/1")
        shadowOf(context.contentResolver).registerInputStream(uri, "from provider".byteInputStream())
        files.add(uri, "provider.log")
        assertArrayEquals("from provider".toByteArray(), readAll().single())
    }

    @Test
    fun `purgeOrphans keeps listed files and deletes leftovers from an earlier process`() {
        staging.mkdirs()
        val leftover = File(staging, "old").apply { writeBytes(byteArrayOf(9)) }
        files.add(byteArrayOf(1), "keep.bin")
        runBlocking { files.purgeOrphans() }
        assertFalse(leftover.exists())
        assertEquals(1, readAll().size)
    }

    @Test
    fun `adding bytes writes nothing on the caller's thread`() {
        val held = HeldDispatcher()
        val deferred = AppFileAttachments(context.contentResolver, { staging }, logger, scope, held)
        deferred.add("hello".toByteArray(), "log.txt")
        assertFalse("nothing may be staged before the SDK's own queue runs", staging.exists())

        held.runAll()
        assertEquals(1, staging.listFiles()!!.size)
    }

    @Test
    fun `the host reusing its array after the call does not change the attachment`() {
        val bytes = "hello".toByteArray()
        files.add(bytes, "log.txt")
        bytes.fill(0)
        assertArrayEquals("hello".toByteArray(), readAll().single())
    }

    @Test
    fun `clear right after add drops the file even if it was not staged yet`() {
        val held = HeldDispatcher()
        val deferred = AppFileAttachments(context.contentResolver, { staging }, logger, scope, held)
        deferred.add(byteArrayOf(1), "a.bin")
        deferred.clear()
        held.runAll()
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    // D2: stagingDir must never be resolved on the caller's thread — Context.getFilesDir() (or an
    // app.filesDir-derived path) can create a directory on first access, which is disk I/O.
    @Test
    fun `stagingDir is resolved only on the SDK's own queue, never on the caller's thread`() {
        val callerThread = Thread.currentThread()
        val resolved = CountDownLatch(1)
        var resolvedOn: Thread? = null
        val recordingStagingDir = { resolvedOn = Thread.currentThread(); resolved.countDown(); staging }
        val deferred = AppFileAttachments(context.contentResolver, recordingStagingDir, logger, scope)

        deferred.add(byteArrayOf(1), "a.bin")

        assertTrue("stagingDir was never resolved", resolved.await(5, TimeUnit.SECONDS))
        assertNotEquals("stagingDir() must resolve off the caller's thread", callerThread, resolvedOn)
    }

    /** Holds dispatched work until [runAll]; stands in for the IO pool. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }
}

package io.github.feedbacklib.android.internal.report

import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DraftStoreTest {

    @TempDir
    lateinit var temp: File

    private val now = 1_700_000_000_000L
    private val day = 24 * 60 * 60 * 1000L
    private var clock = now
    private val logger = SdkLogger(LogLevel.NONE)
    /** Picked copies the sanitizer saw (name, bytes); [sanitize] re-encodes each, by default as a copy written as [encodeAs]. */
    private val sanitized = mutableListOf<Pair<String, ByteArray>>()
    private var encodeAs: ReencodedFormat = ReencodedFormat.PNG
    private var sanitize: (File, File) -> SanitizeResult = { source, target ->
        source.copyTo(target)
        SanitizeResult.Written(encodeAs)
    }
    private val sanitizer = ImageSanitizer { source, target ->
        sanitized += source.name to source.readBytes()
        sanitize(source, target)
    }
    private val store by lazy { DraftStore({ File(temp, "drafts") }, logger, sanitizer, clock = { clock }) }

    private fun purging(): List<String> = File(temp, "drafts").list().orEmpty().filter { it.startsWith(".purging-") }

    private fun capture(name: String = "c.png", bytes: ByteArray = byteArrayOf(1, 2, 3)): File =
        File(temp, "capture/$name").apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }

    private fun draftFile(name: String): File = File(temp, "drafts/d1/$name")

    private fun addPng(bytes: ByteArray = byteArrayOf(7), max: Int = 4): AddImageResult =
        store.addImage("d1", { "image/png" }, { bytes.inputStream() }, max, 1_000)

    @Test
    fun `adopting moves the screenshot into the draft`() {
        val source = capture()
        val adopted = store.adoptScreenshot("d1", source)
        assertFalse(source.exists())
        assertEquals(draftFile("screenshot.png"), adopted)
        assertArrayEquals(byteArrayOf(1, 2, 3), adopted!!.readBytes())
    }

    @Test
    fun `adopting over an existing screenshot replaces it without deleting it first`() {
        store.adoptScreenshot("d1", capture("a.png", byteArrayOf(1)))
        store.adoptScreenshot("d1", capture("b.png", byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(2), draftFile("screenshot.png").readBytes())
    }

    @Test
    fun `adopting a missing screenshot does nothing`() {
        assertNull(store.adoptScreenshot("d1", File(temp, "gone.png")))
        assertFalse(File(temp, "drafts/d1").exists())
    }

    @Test
    fun `attachments list the files the report will carry, with their version, and nothing else`() {
        store.adoptScreenshot("d1", capture())
        draftFile("notes.txt").writeText("not an attachment")
        draftFile("screenshot.png.tmp").writeBytes(byteArrayOf(1))
        store.writeEdits("d1", "screenshot.png", "{}")
        store.writeCaptureState("d1", "{}")

        val files = store.attachments("d1")
        assertEquals(listOf(AttachmentKind.SCREENSHOT), files.map { it.kind })
        val shot = files.single()
        assertEquals("screenshot.png", shot.fileName)
        assertEquals("image/png", shot.mimeType)
        assertEquals(3L, shot.sizeBytes)
        assertEquals(draftFile("screenshot.png").lastModified(), shot.lastModified)
        assertTrue(store.attachments("nobody").isEmpty())
    }

    @Test
    fun `a gallery image enters the draft re-encoded, named by the format written`() {
        encodeAs = ReencodedFormat.JPEG
        val result = store.addImage("d1", { "image/heic" }, { byteArrayOf(1, 2).inputStream() }, 4, 1_000)
        val added = (result as AddImageResult.Added).file
        assertEquals("gallery-$now.jpg", added.fileName)
        assertEquals(AttachmentKind.GALLERY_IMAGE, added.kind)
        assertEquals("image/jpeg", added.mimeType)
        assertArrayEquals(byteArrayOf(1, 2), added.file.readBytes())
        encodeAs = ReencodedFormat.PNG
        val png = (store.addImage("d1", { "image/jpeg" }, { byteArrayOf(3).inputStream() }, 4, 1_000) as AddImageResult.Added).file
        assertEquals("image/png", png.mimeType)
        assertTrue(png.fileName.endsWith(".png"))
    }

    @Test
    fun `the re-encoded file, not the picked copy, is what the draft keeps`() {
        sanitize = { source, target ->
            assertTrue(File(temp, "drafts/d1").list().orEmpty().none { it.startsWith("gallery-") }, "nothing in the draft before re-encoding")
            assertArrayEquals(byteArrayOf(1, 2), source.readBytes(), "the complete copy")
            target.writeBytes(byteArrayOf(9))
            SanitizeResult.Written(ReencodedFormat.JPEG)
        }
        val added = (store.addImage("d1", { "image/jpeg" }, { byteArrayOf(1, 2).inputStream() }, 4, 1_000) as AddImageResult.Added).file
        assertEquals(1, sanitized.size)
        assertArrayEquals(byteArrayOf(9), added.file.readBytes())
        assertEquals(listOf(added.fileName), File(temp, "drafts/d1").list()!!.toList(), "no temporary file stays after success")
    }

    @Test
    fun `an image that cannot be decoded never enters the draft`() {
        sanitize = { _, target ->
            target.writeBytes(byteArrayOf(1)) // a half-written encoding
            SanitizeResult.Failed
        }
        assertEquals(AddImageResult.Failed, store.addImage("d1", { "image/heic" }, { byteArrayOf(1).inputStream() }, 4, 1_000))
        assertTrue(File(temp, "drafts/d1").listFiles().orEmpty().isEmpty(), "no copy may stay")
    }

    @Test
    fun `an image whose encoding stays over the limit is too large`() {
        sanitize = { _, target ->
            target.writeBytes(byteArrayOf(1)) // cut short at the limit
            SanitizeResult.TooLarge
        }
        assertEquals(AddImageResult.TooLarge, store.addImage("d1", { "image/png" }, { byteArrayOf(1).inputStream() }, 4, 1_000))
        assertTrue(File(temp, "drafts/d1").listFiles().orEmpty().isEmpty(), "no copy may stay")
    }

    @Test
    fun `a sanitizer that throws drops the image instead of crashing`() {
        sanitize = { _, target ->
            target.writeBytes(byteArrayOf(1))
            throw IOException("disk full")
        }
        assertEquals(AddImageResult.Failed, store.addImage("d1", { "image/jpeg" }, { byteArrayOf(1).inputStream() }, 4, 1_000))
        assertTrue(File(temp, "drafts/d1").listFiles().orEmpty().isEmpty(), "no copy may stay")
    }

    @Test
    fun `refused and oversized images never reach the sanitizer`() {
        store.addImage("d1", { "text/plain" }, { byteArrayOf(1).inputStream() }, 4, 1_000)
        store.addImage("d1", { "image/png" }, { ByteArray(1_001).inputStream() }, 4, 1_000)
        assertTrue(sanitized.isEmpty())
        assertTrue(File(temp, "drafts/d1").listFiles().orEmpty().isEmpty(), "no partial copy may stay")
    }

    @Test
    fun `non-standard names of png and jpeg are accepted`() {
        assertEquals("jpg", MimeTypes.imageExtension("image/jpg"))
        assertEquals("png", MimeTypes.imageExtension("image/x-png"))
        assertEquals("jpg", MimeTypes.imageExtension("IMAGE/JPEG; q=1"))
        assertNull(MimeTypes.imageExtension("image/bmp"))
        assertTrue(store.addImage("d1", { "image/jpg" }, { byteArrayOf(1).inputStream() }, 4, 1_000) is AddImageResult.Added)
    }

    @Test
    fun `two images added in the same millisecond get different names`() {
        val first = (addPng() as AddImageResult.Added).file.fileName
        val second = (addPng() as AddImageResult.Added).file.fileName
        assertEquals("gallery-$now.png", first)
        assertEquals("gallery-${now + 1}.png", second)
    }

    @Test
    fun `an image that is not an allowed type is refused before it is read`() {
        var opened = 0
        val open = { opened++; byteArrayOf(1).inputStream() }
        assertEquals(AddImageResult.Unsupported, store.addImage("d1", { "text/plain" }, open, 4, 1_000))
        assertEquals(AddImageResult.Unsupported, store.addImage("d1", { null }, open, 4, 1_000))
        assertEquals(0, opened)
        assertTrue(store.attachments("d1").isEmpty())
    }

    @Test
    fun `an image over the size cap leaves nothing behind`() {
        assertEquals(AddImageResult.TooLarge, store.addImage("d1", { "image/png" }, { ByteArray(1_001).inputStream() }, 4, 1_000))
        assertTrue(File(temp, "drafts/d1").listFiles().orEmpty().isEmpty(), "no partial copy may stay")
    }

    @Test
    fun `an image beyond the attachment limit is refused without being read`() {
        store.adoptScreenshot("d1", capture())
        repeat(3) { addPng() }
        var opened = 0
        val result = store.addImage("d1", { "image/png" }, { opened++; byteArrayOf(1).inputStream() }, 4, 1_000)
        assertEquals(AddImageResult.LimitReached, result)
        assertEquals(0, opened)
        assertEquals(4, store.attachments("d1").size)
    }

    @Test
    fun `an unreadable source is a failure, not a crash`() {
        assertEquals(AddImageResult.Failed, store.addImage("d1", { "image/png" }, { null }, 4, 1_000))
        assertEquals(AddImageResult.Failed, store.addImage("d1", { "image/png" }, { error("provider died") }, 4, 1_000))
    }

    @Test
    fun `a finished recording joins the draft after the images as mp4 video`() {
        addPng()
        val added = (store.adoptRecording("d1", capture("rec.mp4"), 4) as AddImageResult.Added).file
        assertEquals(AttachmentKind.SCREEN_RECORDING, added.kind)
        assertEquals("video/mp4", added.mimeType)
        assertTrue(added.fileName.matches(Regex("recording-\\d+\\.mp4")), added.fileName)
        assertEquals(listOf(AttachmentKind.GALLERY_IMAGE, AttachmentKind.SCREEN_RECORDING), store.attachments("d1").map { it.kind })
    }

    @Test
    fun `a recording refused at the limit is deleted and a missing one fails`() {
        repeat(4) { addPng() }
        val source = capture("rec.mp4")
        assertEquals(AddImageResult.LimitReached, store.adoptRecording("d1", source, 4))
        assertFalse(source.exists())
        assertEquals(AddImageResult.Failed, store.adoptRecording("d1", File(temp, "missing.mp4"), 4))
    }

    @Test
    fun `the automatic recording lands under its own name after every other kind`() {
        store.adoptScreenshot("d1", capture())
        assertEquals(draftFile("auto-recording.mp4"), store.adoptAutoRecording("d1", capture("auto.mp4")))
        store.adoptRecording("d1", capture("rec.mp4"), 4)
        assertEquals(
            listOf(AttachmentKind.SCREENSHOT, AttachmentKind.SCREEN_RECORDING, AttachmentKind.AUTO_SCREEN_RECORDING),
            store.attachments("d1").map { it.kind },
        )
        assertNull(store.adoptAutoRecording("d1", File(temp, "capture/auto.mp4")), "already adopted")
    }

    @Test
    fun `a recording is never replaced by an edit`() {
        val added = (store.adoptRecording("d1", capture("rec.mp4"), 4) as AddImageResult.Added).file
        assertNull(store.replaceEdited("d1", added.fileName) { it.write(1); true })
        assertTrue(added.file.exists())
    }

    @Test
    fun `an extra screenshot is moved in under its own kind and refused at the limit`() {
        val source = capture()
        val added = (store.adoptExtraScreenshot("d1", source, 4) as AddImageResult.Added).file
        assertEquals("extra-$now.png", added.fileName)
        assertEquals(AttachmentKind.EXTRA_SCREENSHOT, added.kind)
        assertFalse(source.exists())

        repeat(3) { addPng() }
        val late = capture("late.png")
        assertEquals(AddImageResult.LimitReached, store.adoptExtraScreenshot("d1", late, 4))
        assertFalse(late.exists(), "a refused capture must not linger in the cache")
        assertEquals(AddImageResult.Failed, store.adoptExtraScreenshot("d1", File(temp, "gone.png"), 4))
    }

    @Test
    fun `an extra screenshot that cannot be moved in is deleted from the cache`() {
        File(temp, "drafts").mkdirs()
        File(temp, "drafts/d1").writeText("a file where the draft directory should be")
        val source = capture()
        assertEquals(AddImageResult.Failed, store.adoptExtraScreenshot("d1", source, 4))
        assertFalse(source.exists(), "a capture that could not be adopted must not linger in the cache")
    }

    @Test
    fun `a recording or an automatic recording that cannot be moved in is deleted from the cache`() {
        File(temp, "drafts").mkdirs()
        File(temp, "drafts/d1").writeText("a file where the draft directory should be")

        val recording = capture("rec.mp4")
        assertEquals(AddImageResult.Failed, store.adoptRecording("d1", recording, 4))
        assertFalse(recording.exists(), "a recording that could not be adopted must not linger in the cache")

        val auto = capture("auto.mp4")
        assertNull(store.adoptAutoRecording("d1", auto))
        assertFalse(auto.exists(), "an automatic recording that could not be adopted must not linger in the cache")
    }

    @Test
    fun `the strip shows the invocation screenshot, then extra screenshots, then images, oldest first`() {
        clock = 999L
        addPng()
        store.adoptExtraScreenshot("d1", capture("x.png"), 4)
        clock = 1_000L
        store.adoptExtraScreenshot("d1", capture("y.png"), 4)
        store.adoptScreenshot("d1", capture("z.png"))
        assertEquals(
            listOf("screenshot.png", "extra-999.png", "extra-1000.png", "gallery-999.png"),
            store.attachments("d1").map { it.fileName },
        )
    }

    @Test
    fun `an edited png replaces the original in place and forgets its edits`() {
        store.adoptScreenshot("d1", capture())
        store.writeEdits("d1", "screenshot.png", "{\"steps\":[]}")
        val saved = store.replaceEdited("d1", "screenshot.png") { out -> out.write(byteArrayOf(9)); true }
        assertEquals("screenshot.png", saved!!.fileName)
        assertEquals(AttachmentKind.SCREENSHOT, saved.kind)
        assertArrayEquals(byteArrayOf(9), draftFile("screenshot.png").readBytes())
        assertNull(store.readEdits("d1", "screenshot.png"))
    }

    @Test
    fun `an edited jpeg becomes a png that keeps its kind and place`() {
        encodeAs = ReencodedFormat.JPEG
        val jpeg = (store.addImage("d1", { "image/jpeg" }, { byteArrayOf(1).inputStream() }, 4, 1_000) as AddImageResult.Added).file
        val saved = store.replaceEdited("d1", jpeg.fileName) { out -> out.write(byteArrayOf(9)); true }
        assertEquals("gallery-$now.png", saved!!.fileName)
        assertEquals(AttachmentKind.GALLERY_IMAGE, saved.kind)
        assertFalse(jpeg.file.exists())
        assertEquals(listOf("gallery-$now.png"), store.attachments("d1").map { it.fileName })
    }

    @Test
    fun `the editor steps are forgotten before the edited image replaces the original`() {
        encodeAs = ReencodedFormat.JPEG
        val jpeg = (store.addImage("d1", { "image/jpeg" }, { byteArrayOf(1).inputStream() }, 4, 1_000) as AddImageResult.Added).file
        store.writeEdits("d1", jpeg.fileName, "{\"steps\":[1]}")
        // A non-empty directory in the png's place makes the replace fail, standing in for a kill
        // between the two steps: the steps must already be gone, the original still there.
        File(temp, "drafts/d1/gallery-$now.png/blocker").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }
        assertNull(store.replaceEdited("d1", jpeg.fileName) { out -> out.write(byteArrayOf(9)); true })
        assertNull(store.readEdits("d1", jpeg.fileName))
        assertArrayEquals(byteArrayOf(1), jpeg.file.readBytes())
    }

    @Test
    fun `an original left beside its edited png is not listed and is deleted`() {
        draftFile("gallery-5.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        draftFile("gallery-5.png").writeBytes(byteArrayOf(9))
        draftFile("gallery-6.jpg").writeBytes(byteArrayOf(2))
        store.writeEdits("d1", "gallery-5.jpg", "stale")

        assertEquals(listOf("gallery-5.png", "gallery-6.jpg"), store.attachments("d1").map { it.fileName })
        assertFalse(draftFile("gallery-5.jpg").exists())
        assertNull(store.readEdits("d1", "gallery-5.jpg"))
    }

    @Test
    fun `a failed edit keeps the original and leaves no temporary file`() {
        store.adoptScreenshot("d1", capture())
        assertNull(store.replaceEdited("d1", "screenshot.png") { false })
        assertNull(store.replaceEdited("d1", "screenshot.png") { error("encoder died") })
        assertArrayEquals(byteArrayOf(1, 2, 3), draftFile("screenshot.png").readBytes())
        assertEquals(listOf("screenshot.png"), File(temp, "drafts/d1").list()!!.toList())
        assertNull(store.replaceEdited("d1", "missing.png") { true })
    }

    @Test
    fun `edits are kept per attachment and removing the attachment removes them`() {
        store.adoptScreenshot("d1", capture())
        store.writeEdits("d1", "screenshot.png", "one")
        store.writeEdits("d1", "screenshot.png", "two")
        assertEquals("two", store.readEdits("d1", "screenshot.png"))

        assertTrue(store.remove("d1", "screenshot.png"))
        assertNull(store.readEdits("d1", "screenshot.png"))
        store.deleteEdits("d1", "screenshot.png") // nothing to delete: still fine
    }

    @Test
    fun `the newest saved capture mode wins and older ones are dropped`() {
        store.writeCaptureState("old", "first")
        File(temp, "drafts/old/capture.json").setLastModified(now - 60_000)
        store.writeCaptureState("new", "second")

        assertEquals(PendingCapture("new", "second"), store.pendingCapture())
        assertFalse(File(temp, "drafts/old/capture.json").exists())

        store.clearCaptureState("new")
        assertNull(store.pendingCapture())
    }

    @Test
    fun `a capture state in a draft the purge will delete is left for the purge`() {
        store.writeCaptureState("stale", "gone")
        File(temp, "drafts/stale/capture.json").setLastModified(now - day - 1)
        File(temp, "drafts/stale").setLastModified(now - day - 1)

        assertNull(store.pendingCapture())
        assertTrue(File(temp, "drafts/stale/capture.json").exists(), "left alone, so the draft stays stale")

        store.purgeStale()
        assertFalse(File(temp, "drafts/stale").exists())
    }

    @Test
    fun `an unreadable capture state does not cost the others`() {
        store.writeCaptureState("old", "first")
        File(temp, "drafts/old/capture.json").setLastModified(now - 60_000)
        store.writeCaptureState("new", "second")

        val pending = store.pendingCapture { file ->
            if (file.parentFile!!.name == "new") throw FileNotFoundException("vanished") else file.readText()
        }
        assertEquals(PendingCapture("old", "first"), pending)
        assertTrue(File(temp, "drafts/new/capture.json").exists(), "an unreadable state is skipped, not dropped")
    }

    @Test
    fun `removing an attachment deletes its file but never escapes the draft`() {
        store.adoptScreenshot("d1", capture())
        val outside = File(temp, "drafts/keep.txt").apply { writeText("x") }
        assertFalse(store.remove("d1", "../keep.txt"))
        assertTrue(outside.exists())
        assertTrue(store.remove("d1", "screenshot.png"))
        assertTrue(store.attachments("d1").isEmpty())
    }

    @Test
    fun `delete removes the whole draft`() {
        store.adoptScreenshot("d1", capture())
        store.delete("d1")
        assertFalse(File(temp, "drafts/d1").exists())
        store.delete("d1")
    }

    @Test
    fun `an invalid draft id is refused without throwing and without touching the source`() {
        val source = capture()
        assertNull(store.adoptScreenshot("../x", source))
        assertTrue(source.exists())
        assertTrue(store.attachments("../x").isEmpty())
        assertFalse(store.remove("../x", "screenshot.png"))
        assertEquals(AddImageResult.Failed, store.addImage("../x", { "image/png" }, { byteArrayOf(1).inputStream() }, 4, 1_000))
        assertNull(store.replaceEdited("../x", "screenshot.png") { true })
        assertNull(store.readEdits("../x", "screenshot.png"))
        store.writeEdits("../x", "screenshot.png", "{}")
        store.writeCaptureState("../x", "{}")
        store.delete("../x")
    }

    @Test
    fun `purge removes drafts untouched for a day and keeps fresh ones`() {
        store.adoptScreenshot("old", capture("a.png"))
        store.adoptScreenshot("fresh", capture("b.png"))
        File(temp, "drafts/old/screenshot.png").setLastModified(now - day - 1)
        File(temp, "drafts/old").setLastModified(now - day - 1)
        File(temp, "drafts/fresh/screenshot.png").setLastModified(now - day + 60_000)
        File(temp, "drafts/fresh").setLastModified(now - day - 1)

        store.purgeStale()

        assertFalse(File(temp, "drafts/old").exists())
        assertTrue(File(temp, "drafts/fresh/screenshot.png").exists())
        assertTrue(purging().isEmpty())
    }

    @Test
    fun `the startup sweep deletes the temporary files of an import the process was killed in`() {
        addPng()
        for (name in listOf("image.tmp", "image-encoded.tmp")) draftFile(name).writeBytes(byteArrayOf(1))
        DraftStore({ File(temp, "drafts") }, logger, sanitizer, clock = { clock }).purgeStale() // a new process: no queue open
        assertEquals(listOf("gallery-$now.png"), File(temp, "drafts/d1").list()!!.toList())
    }

    @Test
    fun `a draft is judged by its files, not by the time of its directory`() {
        addPng()
        draftFile("gallery-$now.png").setLastModified(now - day - 1)
        File(temp, "drafts/d1").setLastModified(now) // as deleting a leftover temporary file there does
        File(temp, "drafts/empty").apply { mkdirs() }.setLastModified(now)
        DraftStore({ File(temp, "drafts") }, logger, sanitizer, clock = { clock }).purgeStale()
        assertFalse(File(temp, "drafts/d1").exists(), "abandoned for a day, whatever its directory says")
        assertTrue(File(temp, "drafts/empty").exists(), "an empty draft goes by its directory")
    }

    @Test
    fun `the startup sweep leaves the temporary files of a draft whose queue is open`() {
        store.queue("d1")
        File(temp, "drafts/d1").mkdirs()
        draftFile("image.tmp").writeBytes(byteArrayOf(1))
        store.purgeStale()
        assertTrue(draftFile("image.tmp").exists(), "an import may be running on the queue")
    }

    @Test
    fun `the startup sweeps judge staleness by one cutoff however the clock moves between them`() {
        store.writeCaptureState("edge", "at the cutoff")
        val cutoff = store.staleCutoff()
        for (path in listOf("edge/capture.json", "edge")) File(temp, "drafts/$path").setLastModified(cutoff)
        clock += 60_000 // the second sweep runs later

        assertEquals(PendingCapture("edge", "at the cutoff"), store.pendingCapture(cutoff))
        store.purgeStale(cutoff)
        assertTrue(File(temp, "drafts/edge/capture.json").exists(), "the draft restored by the first sweep is not purged by the second")
    }

    @Test
    fun `purge removes a draft a killed process left half-deleted, whatever its age`() {
        File(temp, "drafts/.purging-x/screenshot.png").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        store.purgeStale()
        assertTrue(purging().isEmpty())
    }

    @Test
    fun `purge without a drafts directory does nothing`() {
        store.purgeStale()
        assertFalse(File(temp, "drafts").exists())
    }

    @Test
    fun `the startup sweeps leave a draft alone once something opened its queue`() {
        store.adoptScreenshot("live", capture("a.png"))
        store.writeCaptureState("live", "mine")
        store.writeCaptureState("left", "theirs")
        for (path in listOf("live/screenshot.png", "live/capture.json", "live")) File(temp, "drafts/$path").setLastModified(now - day - 1)
        for (path in listOf("left/capture.json", "left")) File(temp, "drafts/$path").setLastModified(now - 2 * day)
        store.queue("live")

        // Past the purge age both drafts would be skipped anyway: a looser age isolates the live-queue rule.
        assertEquals(PendingCapture("left", "theirs"), store.pendingCapture(cutoff = now - 3 * day))
        assertTrue(File(temp, "drafts/live/capture.json").exists(), "a live draft's capture state is not dropped")

        store.purgeStale()
        assertTrue(File(temp, "drafts/live/screenshot.png").exists(), "a live draft is never purged")
        assertFalse(File(temp, "drafts/left").exists())
        assertTrue(purging().isEmpty())
    }

    @Test
    fun `a move where atomic renames are unsupported still replaces the target and leaves no copy behind`() {
        val target = File(temp, "drafts/d1/screenshot.png").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val source = capture(bytes = byteArrayOf(2))
        moveReplacing(source, target, logger) { _, _ -> throw AtomicMoveNotSupportedException(null, null, "test") }
        assertArrayEquals(byteArrayOf(2), target.readBytes())
        assertFalse(source.exists())
        assertEquals(listOf("screenshot.png"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun `a move across file systems copies beside the target and renames the copy into place`() {
        val target = File(temp, "drafts/d1/screenshot.png").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val source = capture(bytes = byteArrayOf(2))
        val moved = mutableListOf<String>()
        moveReplacing(source, target, logger) { from, to ->
            // Only the rename inside the draft directory is atomic: the capture cache is "elsewhere".
            if (from.parent != to.parent) throw AtomicMoveNotSupportedException(null, null, "cross-device")
            moved += from.fileName.toString()
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        assertEquals(listOf("screenshot.png.moving"), moved)
        assertArrayEquals(byteArrayOf(2), target.readBytes())
        assertFalse(source.exists())
        assertEquals(listOf("screenshot.png"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun `a failed copy across file systems keeps the old target and the source`() {
        val target = File(temp, "drafts/d1/screenshot.png").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val source = capture(bytes = byteArrayOf(2))
        val thrown = assertThrows(IOException::class.java) {
            moveReplacing(source, target, logger) { from, _ ->
                if (from == source.toPath()) throw AtomicMoveNotSupportedException(null, null, "cross-device")
                throw IOException("disk full")
            }
        }
        assertEquals("disk full", thrown.message)
        assertArrayEquals(byteArrayOf(1), target.readBytes())
        assertTrue(source.exists())
        assertEquals(listOf("screenshot.png"), target.parentFile!!.list()!!.toList())
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `one draft's work runs in order on its own queue while another draft's runs beside it`() = runBlocking {
        assertSame(store.queue("a"), store.queue("a"))
        assertNotSame(store.queue("a"), store.queue("b"))

        val order = Collections.synchronizedList(mutableListOf<Int>())
        (1..50).map { n -> launch(store.queue("a")) { order += n } }.joinAll()
        assertEquals((1..50).toList(), order)

        // Would deadlock if both drafts shared one queue.
        val bRan = CountDownLatch(1)
        val a = launch(store.queue("a")) { assertTrue(bRan.await(5, TimeUnit.SECONDS)) }
        launch(store.queue("b")) { bRan.countDown() }
        a.join()
    }
}

package io.github.feedbacklib.android.internal.report

import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.internal.core.SdkLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** A file of a report draft that goes into the report. */
internal data class DraftFile(
    val kind: AttachmentKind,
    val file: File,
    val mimeType: String,
    /** With [sizeBytes], tells a file the editor rewrote from its earlier content (thumbnail cache). */
    val lastModified: Long = 0L,
    val sizeBytes: Long = 0L,
) {
    val fileName: String
        get() = file.name
}

/** What became of an image offered to a draft. */
internal sealed interface AddImageResult {
    data class Added(val file: DraftFile) : AddImageResult
    data object LimitReached : AddImageResult
    data object TooLarge : AddImageResult
    data object Unsupported : AddImageResult
    data object Failed : AddImageResult
}

/** A capture mode the previous process left behind (spec §6): its draft and the saved request. */
internal data class PendingCapture(val draftId: String, val json: String)

/**
 * Files of the report the user is still writing (spec §6): `filesDir/feedbackkit/drafts/<draftId>/`.
 * Attachments are the files [kindOf] recognises — the invocation screenshot (moved here from the
 * capture cache), extra screenshots, gallery images and screen recordings (moved here from the
 * recording cache); beside them live the editor's steps (`edits/<file>.json`) and a saved capture
 * mode (`capture.json`).
 *
 * Every operation on a draft — attachments, edits and the capture state alike — is blocking disk
 * I/O and must run on [queue] of its draft: one serial queue per draft, shared by every screen and
 * component that touches it, so a removal queued by one screen is done before a listing queued
 * later by another (a restore racing a close). The only work off a draft's queue is the startup
 * sweeps [pendingCapture] and [purgeStale], which go over all drafts and skip any draft whose queue
 * is open in this process. Nothing but [dir] throws; failures log.
 */
internal class DraftStore(
    private val root: () -> File,
    private val logger: SdkLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    // One small view per draft opened in this process; never removed, so a late task can never
    // end up on a second queue for the same draft.
    private val queues = HashMap<String, CoroutineDispatcher>()

    /** The serial queue of [draftId]'s files: work runs one at a time, in the order it was queued. */
    fun queue(draftId: String): CoroutineDispatcher =
        synchronized(queues) { queues.getOrPut(draftId) { io.limitedParallelism(1) } }

    suspend fun <T> onQueue(draftId: String, block: DraftStore.() -> T): T = withContext(queue(draftId)) { block() }

    /** @throws IllegalArgumentException for an id that is not a plain draft id. */
    fun dir(draftId: String): File {
        require(ID_PATTERN.matches(draftId)) { "Not a draft id: $draftId" }
        return File(root(), draftId)
    }

    /**
     * Moves [source] into the draft as its invocation screenshot, replacing an earlier one only once
     * the new file is in place; `null` when [source] is gone (already adopted, never written) or
     * could not be moved.
     */
    fun adoptScreenshot(draftId: String, source: File): File? =
        try {
            if (!source.isFile) {
                null
            } else {
                File(draftDir(draftId), SCREENSHOT_FILE_NAME).also { moveInto(source, it) }
            }
        } catch (e: Exception) {
            logger.w("Could not move the screenshot into the report draft", e)
            null
        }

    /**
     * Moves a captured extra screenshot into the draft. A capture that does not end up in the draft
     * — refused at the limit or failing to move — is deleted, so it never lingers in the cache.
     */
    fun adoptExtraScreenshot(draftId: String, source: File, maxAttachments: Int): AddImageResult =
        adoptNumbered(draftId, source, maxAttachments, EXTRA_PREFIX, ".png", "extra screenshot")

    /** Moves a finished manual recording into the draft (spec §7); like an extra screenshot, deleted when refused. */
    fun adoptRecording(draftId: String, source: File, maxAttachments: Int): AddImageResult =
        adoptNumbered(draftId, source, maxAttachments, RECORDING_PREFIX, ".mp4", "screen recording")

    /**
     * Moves the automatic recording taken at invocation into the draft (spec §7), beside the invocation
     * screenshot; null when [source] is gone (already adopted) or could not be moved, in which case it
     * is deleted: a clip is megabytes of cache.
     *
     * The caller has already reserved this attachment's slot (it counts towards the attachment limit
     * the moment the invocation takes it, spec §7): this method does not check that limit itself. The
     * fixed [AUTO_RECORDING_FILE_NAME] means a second call replaces an earlier clip already in the
     * draft rather than adding beside it — there is only ever one automatic recording per report.
     */
    fun adoptAutoRecording(draftId: String, source: File): File? =
        try {
            if (!source.isFile) null else File(draftDir(draftId), AUTO_RECORDING_FILE_NAME).also { moveInto(source, it) }
        } catch (e: Exception) {
            logger.w("Could not move the automatic recording into the report draft", e)
            if (source.exists() && !source.delete()) logger.w("Could not delete an automatic recording that was not adopted")
            null
        }

    private fun adoptNumbered(draftId: String, source: File, maxAttachments: Int, prefix: String, suffix: String, what: String): AddImageResult =
        try {
            when {
                !source.isFile -> AddImageResult.Failed
                attachments(draftId).size >= maxAttachments -> {
                    source.delete()
                    AddImageResult.LimitReached
                }
                else -> {
                    val dir = draftDir(draftId)
                    val target = File(dir, uniqueName(dir, prefix, suffix))
                    moveInto(source, target)
                    AddImageResult.Added(draftFile(target)!!)
                }
            }
        } catch (e: Exception) {
            logger.w("Could not move the $what into the report draft", e)
            if (source.exists() && !source.delete()) logger.w("Could not delete a $what that was not adopted")
            AddImageResult.Failed
        }

    /**
     * Copies an image into the draft: an accepted image [mimeType] (checked before anything is
     * read), below [maxAttachments] attachments, at most [maxBytes]. The copy lands under a
     * temporary name and is renamed into place only when complete.
     */
    fun addImage(
        draftId: String,
        mimeType: () -> String?,
        open: () -> InputStream?,
        maxAttachments: Int,
        maxBytes: Long,
    ): AddImageResult {
        var partial: File? = null
        return try {
            val extension = MimeTypes.imageExtension(mimeType())
            when {
                extension == null -> AddImageResult.Unsupported
                attachments(draftId).size >= maxAttachments -> AddImageResult.LimitReached
                else -> {
                    val dir = draftDir(draftId) // before open(): an invalid id must not leak the stream
                    val input = open()
                    if (input == null) {
                        AddImageResult.Failed
                    } else {
                        val tmp = File(dir, IMAGE_TMP_NAME).also { partial = it }
                        val size = input.use { copyLimited(it, tmp, maxBytes) }
                        if (size == null) {
                            AddImageResult.TooLarge
                        } else {
                            val target = File(dir, uniqueName(dir, GALLERY_PREFIX, ".$extension"))
                            moveInto(tmp, target)
                            partial = null
                            AddImageResult.Added(draftFile(target)!!)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.w("Could not add an image to the report draft", e)
            AddImageResult.Failed
        } finally {
            partial?.delete()
        }
    }

    /**
     * The draft's attachments in strip order: by kind, then oldest first. A gallery image whose
     * edited png copy exists (the original's delete failed or the process died right after the
     * edit) is not listed and is deleted.
     */
    fun attachments(draftId: String): List<DraftFile> =
        try {
            val files = dir(draftId).listFiles().orEmpty().filter { it.isFile }.mapNotNull(::draftFile)
            val names = files.mapTo(HashSet()) { it.fileName }
            val (replaced, kept) = files.partition { file ->
                file.kind == AttachmentKind.GALLERY_IMAGE && !file.fileName.endsWith(".png") &&
                    file.fileName.substringBeforeLast('.') + ".png" in names
            }
            replaced.forEach { stale ->
                if (!stale.file.delete()) logger.w("Could not delete an image replaced by its edited copy")
                deleteEdits(draftId, stale.fileName)
            }
            kept.sortedWith(compareBy({ it.kind.ordinal }, { stampOf(it.fileName) }, { it.fileName }))
        } catch (e: Exception) {
            logger.w("Could not list the report draft", e)
            emptyList()
        }

    /** Deletes one attachment and its editor steps; [fileName] must be a bare name inside the draft. */
    fun remove(draftId: String, fileName: String): Boolean =
        try {
            val deleted = File(dir(draftId), requireFileName(fileName)).delete()
            deleteEdits(draftId, fileName)
            deleted
        } catch (e: Exception) {
            logger.w("Could not remove a report draft attachment", e)
            false
        }

    /**
     * Replaces the attachment [fileName] with the PNG [write] produces (it returns false on
     * failure): `<name>.png` in the same place of the strip, the original deleted if its name
     * differed, its editor steps forgotten. Returns the new file, or null when nothing changed.
     *
     * The steps are forgotten before the image is replaced: killed in between, the user loses
     * unsaved steps rather than getting annotations already baked into the image applied twice.
     */
    fun replaceEdited(draftId: String, fileName: String, write: (OutputStream) -> Boolean): DraftFile? {
        var partial: File? = null
        return try {
            val dir = dir(draftId)
            val original = File(dir, requireFileName(fileName))
            val kind = kindOf(fileName)
            // The editor is for images only; a recording is never rewritten (spec §7).
            if (!original.isFile || kind == null || kind.isVideo) return null
            val target = File(dir, fileName.substringBeforeLast('.') + ".png")
            val tmp = File(dir, EDIT_TMP_NAME).also { partial = it }
            val written = tmp.outputStream().use { write(it) }
            if (!written) return null
            deleteEdits(draftId, fileName)
            moveInto(tmp, target)
            partial = null
            // Left behind, it is dropped by the next listing: the png of the same stamp wins.
            if (target != original && !original.delete()) logger.w("Could not delete the image replaced by its edited copy")
            draftFile(target)
        } catch (e: Exception) {
            logger.w("Could not save the edited image", e)
            null
        } finally {
            partial?.delete()
        }
    }

    /** The editor's saved steps for [fileName], or null. */
    fun readEdits(draftId: String, fileName: String): String? =
        try {
            editsFile(draftId, fileName).takeIf { it.isFile }?.readText()
        } catch (e: Exception) {
            logger.w("Could not read the saved annotation steps", e)
            null
        }

    fun writeEdits(draftId: String, fileName: String, json: String) {
        try {
            writeAtomically(editsFile(draftId, fileName), json)
        } catch (e: Exception) {
            logger.w("Could not save the annotation steps", e)
        }
    }

    fun deleteEdits(draftId: String, fileName: String) {
        try {
            editsFile(draftId, fileName).delete()
        } catch (e: Exception) {
            logger.w("Could not delete the annotation steps", e)
        }
    }

    /** Remembers that [draftId] waits in capture mode, so a new process can bring it back (spec §6). */
    fun writeCaptureState(draftId: String, json: String) {
        try {
            writeAtomically(File(dir(draftId), CAPTURE_STATE_FILE_NAME), json)
        } catch (e: Exception) {
            logger.w("Could not save the screenshot capture state", e)
        }
    }

    fun clearCaptureState(draftId: String) {
        try {
            File(dir(draftId), CAPTURE_STATE_FILE_NAME).delete()
        } catch (e: Exception) {
            logger.w("Could not clear the screenshot capture state", e)
        }
    }

    /**
     * The newest capture mode a previous process left behind; older ones were abandoned and are
     * dropped (their drafts go with [purgeStale]). A draft whose queue is open belongs to this
     * process and is left alone. A capture state that cannot be read is skipped and kept, so it
     * never costs the others. A draft [purgeStale] would delete (nothing in it touched for
     * [cutoff], the same test) is skipped and left alone, so it goes whole, capture state included,
     * rather than coming back without its files. Pass [purgeStale] the same [cutoff] on the same
     * startup pass, so a draft this brings back is never one that purge deletes. Call once per
     * process start, off the main thread.
     */
    fun pendingCapture(cutoff: Long = staleCutoff(), read: (File) -> String = File::readText): PendingCapture? =
        try {
            val saved = root().listFiles().orEmpty()
                .filter { it.isDirectory && ID_PATTERN.matches(it.name) && !isStale(it, cutoff) }
                .map { File(it, CAPTURE_STATE_FILE_NAME) }
                .filter { it.isFile }
                .sortedByDescending { it.lastModified() }
            var newest: PendingCapture? = null
            for (file in saved) {
                val draftId = file.parentFile!!.name
                try {
                    unlessLive(draftId) {
                        if (newest == null) newest = PendingCapture(draftId, read(file)) else file.delete()
                    }
                } catch (e: Exception) {
                    logger.w("Could not read a saved screenshot capture state", e)
                }
            }
            newest
        } catch (e: Exception) {
            logger.w("Could not look for a saved screenshot capture state", e)
            null
        }

    fun delete(draftId: String) {
        try {
            val dir = dir(draftId)
            if (dir.exists() && !dir.deleteRecursively()) logger.w("Could not delete report draft $draftId")
        } catch (e: Exception) {
            logger.w("Could not delete a report draft", e)
        }
    }

    /** The staleness cutoff of one startup pass: a draft untouched since then is abandoned. */
    fun staleCutoff(maxAgeMillis: Long = STALE_DRAFT_MAX_AGE_MILLIS): Long = clock() - maxAgeMillis

    /**
     * Deletes drafts nothing has touched since [cutoff]: screens that were finished without
     * cleaning up (process killed right after) or never came back. A younger draft may belong to a
     * FeedbackActivity the system is restoring right now, or wait in capture mode, so it stays; so
     * does any draft whose queue is open. A stale draft is renamed out of the way while no queue can
     * open for it, then deleted outside the lock; one left half-deleted by a killed process goes on
     * the next start.
     */
    fun purgeStale(cutoff: Long = staleCutoff()) {
        try {
            val root = root()
            root.listFiles()?.forEach { draft ->
                if (!draft.isDirectory) return@forEach
                if (draft.name.startsWith(PURGING_PREFIX)) {
                    deleteAbandoned(draft)
                    return@forEach
                }
                if (!isStale(draft, cutoff)) return@forEach
                val purging = File(root, PURGING_PREFIX + draft.name)
                var claimed = false
                unlessLive(draft.name) { claimed = draft.renameTo(purging) }
                if (claimed) deleteAbandoned(purging)
            }
        } catch (e: Exception) {
            logger.e("Could not purge abandoned report drafts", e)
        }
    }

    /** Nothing in [draft] was touched since [cutoff]: the one staleness test of [purgeStale] and [pendingCapture]. */
    private fun isStale(draft: File, cutoff: Long): Boolean = (draft.walk().maxOfOrNull { it.lastModified() } ?: 0L) < cutoff

    private fun deleteAbandoned(dir: File) {
        if (!dir.deleteRecursively()) logger.w("Could not delete an abandoned report draft")
    }

    private fun draftDir(draftId: String): File = dir(draftId).apply { mkdirs() }

    private fun draftFile(file: File): DraftFile? =
        kindOf(file.name)?.let { kind -> DraftFile(kind, file, MimeTypes.guess(file.name), file.lastModified(), file.length()) }

    private fun editsFile(draftId: String, fileName: String): File =
        File(File(dir(draftId), EDITS_DIR), requireFileName(fileName) + ".json")

    /** `<prefix><now><suffix>`, moved on by a millisecond while a file with that stamp exists. */
    private fun uniqueName(dir: File, prefix: String, suffix: String): String {
        var stamp = clock()
        while (dir.list().orEmpty().any { it.startsWith("$prefix$stamp.") }) stamp++
        return "$prefix$stamp$suffix"
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            tmp.writeText(text)
            moveInto(tmp, target)
        } finally {
            tmp.delete()
        }
    }

    private fun moveInto(source: File, target: File) = moveReplacing(source, target, logger)

    /**
     * Runs [block] unless [draftId] has a queue in this process. The check and [block] hold the
     * queue lock, so no queue can open for the draft while [block] touches it: keep [block] to one
     * rename, delete or small read.
     */
    private inline fun unlessLive(draftId: String, block: () -> Unit) {
        synchronized(queues) { if (draftId !in queues) block() }
    }

    private fun requireFileName(fileName: String): String {
        require(fileName.isNotEmpty() && fileName == File(fileName).name && fileName != "." && fileName != "..") { "Not a draft file name: $fileName" }
        return fileName
    }

    companion object {
        const val SCREENSHOT_FILE_NAME: String = "screenshot.png"
        const val CAPTURE_STATE_FILE_NAME: String = "capture.json"
        const val EDITS_DIR: String = "edits"
        const val STALE_DRAFT_MAX_AGE_MILLIS: Long = 24 * 60 * 60 * 1000L
        const val AUTO_RECORDING_FILE_NAME: String = "auto-recording.mp4"
        private const val EXTRA_PREFIX = "extra-"
        private const val GALLERY_PREFIX = "gallery-"
        private const val RECORDING_PREFIX = "recording-"
        private const val TMP_SUFFIX = ".tmp"
        private const val IMAGE_TMP_NAME = "image.tmp"
        private const val EDIT_TMP_NAME = "edit.tmp"

        /** A stale draft renamed out of the way and being deleted; never a valid draft id. */
        private const val PURGING_PREFIX = ".purging-"
        private val ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
        private val EXTRA_NAME = Regex("extra-(\\d{1,18})\\.png")
        private val GALLERY_NAME = Regex("gallery-(\\d{1,18})\\.(png|jpg|webp|gif|heic|heif)")
        private val RECORDING_NAME = Regex("recording-(\\d{1,18})\\.mp4")

        /** Which attachment a draft file is; null for anything else in the draft directory. */
        fun kindOf(fileName: String): AttachmentKind? = when {
            fileName == SCREENSHOT_FILE_NAME -> AttachmentKind.SCREENSHOT
            EXTRA_NAME.matches(fileName) -> AttachmentKind.EXTRA_SCREENSHOT
            GALLERY_NAME.matches(fileName) -> AttachmentKind.GALLERY_IMAGE
            RECORDING_NAME.matches(fileName) -> AttachmentKind.SCREEN_RECORDING
            fileName == AUTO_RECORDING_FILE_NAME -> AttachmentKind.AUTO_SCREEN_RECORDING
            else -> null
        }

        private fun stampOf(fileName: String): Long =
            (EXTRA_NAME.matchEntire(fileName) ?: GALLERY_NAME.matchEntire(fileName) ?: RECORDING_NAME.matchEntire(fileName))
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }
}

/**
 * Moves [source] over [target] so that the old target disappears only once the new content is in
 * place. On one file system an atomic rename that replaces the target. When [atomicMove] is not
 * supported for the pair (across file systems), [source] is copied beside the target and the copy
 * renamed into place, so a failed copy never leaves a truncated target and keeps [source].
 *
 * That rename stays in one directory and is atomic on Linux, Android and NTFS. Only on a file
 * system that cannot rename atomically at all does it fall back to a plain replacing move, which
 * unlinks the target before moving the copy in: practically unreachable, and still never a
 * truncated file.
 */
internal fun moveReplacing(
    source: File,
    target: File,
    logger: SdkLogger,
    atomicMove: (Path, Path) -> Unit = ::atomicReplace,
) {
    try {
        atomicMove(source.toPath(), target.toPath())
        return
    } catch (e: AtomicMoveNotSupportedException) {
        // Across file systems, or one without atomic renames: copy beside the target below.
    }
    // Its own suffix: a source named `<target>.tmp` (an atomic write) must not be its own copy.
    val copy = File(target.parentFile, target.name + ".moving")
    try {
        source.copyTo(copy, overwrite = true)
        try {
            atomicMove(copy.toPath(), target.toPath())
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(copy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Exception) {
        copy.delete()
        throw e
    }
    if (!source.delete()) logger.w("Could not delete ${source.name} after copying it into the report draft")
}

private fun atomicReplace(source: Path, target: Path) {
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

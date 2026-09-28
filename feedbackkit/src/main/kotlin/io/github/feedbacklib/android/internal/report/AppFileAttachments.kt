package io.github.feedbacklib.android.internal.report

import android.content.ContentResolver
import android.net.Uri
import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.queue.ReportStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Files the host attached with `FeedbackKit.addFileAttachment` (spec §4, §9): at most three,
 * each at most 5 MB, read when a report is queued so it carries their latest content. They go into
 * every report and never into the report screen's strip or its limit (spec §6).
 *
 * Every change runs on one serial queue in [scope], in call order, so the host's thread (often
 * main) never touches the disk — [stagingDir] itself is resolved only on that queue, the same as
 * `Context.getFilesDir()` may create a directory on first access — and a clear() right after an
 * add() still wins. [snapshot] runs on the same queue, after everything asked for before it.
 */
internal class AppFileAttachments(
    private val contentResolver: ContentResolver,
    private val stagingDir: () -> File,
    private val logger: SdkLogger,
    private val scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
) {

    private sealed interface Source {
        val fileName: String
    }

    private class UriSource(val uri: Uri, override val fileName: String) : Source

    private class StagedSource(val file: File, override val fileName: String) : Source

    private val queue: CoroutineDispatcher = io.limitedParallelism(1)

    // Touched only on [queue].
    private val sources = ArrayDeque<Source>()

    fun add(uri: Uri, fileName: String) {
        scope.launch(queue) { push(UriSource(uri, fileName)) }
    }

    fun add(bytes: ByteArray, fileName: String) {
        if (bytes.size > MAX_FILE_BYTES) {
            logger.w("File attachment $fileName rejected: larger than $MAX_FILE_BYTES bytes")
            return
        }
        // A copy in memory, no disk: the host may reuse its array as soon as the call returns.
        val copy = bytes.copyOf()
        scope.launch(queue) { stage(copy, fileName) }
    }

    fun clear() {
        scope.launch(queue) {
            sources.forEach(::discard)
            sources.clear()
        }
    }

    /**
     * Attachments for the report being queued now: which files, as of everything asked for before
     * this call. Their `open` lambdas run later, when [ReportStore.enqueue] copies them, off this
     * queue: a clear() or a fourth add() in between can delete a staged copy first, and that file
     * is then skipped as unavailable.
     */
    suspend fun snapshot(): List<ReportStore.NewAttachment> = withContext(queue) {
        sources.map { source ->
            val meta = AttachmentMeta(
                id = UUID.randomUUID().toString(),
                kind = AttachmentKind.APP_FILE,
                fileName = source.fileName,
                mimeType = MimeTypes.guess(source.fileName),
                sizeBytes = 0,
            )
            val open: () -> java.io.InputStream? = when (source) {
                is UriSource -> { { contentResolver.openInputStream(source.uri) } }
                is StagedSource -> { { source.file.takeIf { it.exists() }?.inputStream() } }
            }
            ReportStore.NewAttachment(meta, MAX_FILE_BYTES.toLong(), open)
        }
    }

    /** Deletes staged copies no current entry refers to — leftovers of an earlier process. */
    suspend fun purgeOrphans() {
        withContext(queue) {
            val live = sources.filterIsInstance<StagedSource>().map { it.file.name }.toSet()
            stagingDir().listFiles()?.filter { it.name !in live }?.forEach { it.delete() }
        }
    }

    private fun stage(bytes: ByteArray, fileName: String) {
        try {
            val dir = stagingDir().apply { mkdirs() }
            val file = File(dir, UUID.randomUUID().toString())
            file.writeBytes(bytes)
            push(StagedSource(file, fileName))
        } catch (e: Exception) {
            logger.w("File attachment $fileName rejected: cannot stage it", e)
        }
    }

    private fun push(source: Source) {
        if (sources.size == MAX_FILES) {
            val oldest = sources.removeFirst()
            discard(oldest)
            logger.w("File attachment ${oldest.fileName} dropped: only $MAX_FILES files are kept")
        }
        sources.addLast(source)
    }

    private fun discard(source: Source) {
        if (source is StagedSource) source.file.delete()
    }

    internal companion object {
        const val MAX_FILES: Int = 3
        const val MAX_FILE_BYTES: Int = 5 * 1024 * 1024
    }
}

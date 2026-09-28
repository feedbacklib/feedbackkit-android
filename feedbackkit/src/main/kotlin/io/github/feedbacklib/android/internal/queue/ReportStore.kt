package io.github.feedbacklib.android.internal.queue

import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.report.AttachmentMeta
import io.github.feedbacklib.android.internal.report.ReportJson
import io.github.feedbacklib.android.internal.report.ReportPayload
import io.github.feedbacklib.android.internal.report.copyLimited
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Reports waiting for delivery (spec §9). Each report is written into a `.tmp` directory and
 * renamed into place only when complete, so a process death mid-write never leaves a
 * half-report that could be sent. `.tmp` of an enqueue in progress is left alone by [cleanUp].
 */
internal class ReportStore(
    private val root: File,
    private val logger: SdkLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limits: Limits = Limits(),
) {

    data class Limits(
        val maxReports: Int = 20,
        val maxTotalBytes: Long = 100L * 1024 * 1024,
        val maxAgeMillis: Long = 14L * 24 * 60 * 60 * 1000,
    )

    /** An attachment to copy into the store; [open] returning null or throwing skips it. */
    class NewAttachment(
        val meta: AttachmentMeta,
        val maxBytes: Long = Long.MAX_VALUE,
        val open: () -> InputStream?,
    )

    // Names of .tmp directories being written; guarded by this.
    private val inFlight = HashSet<String>()

    /**
     * Copies the attachments into a `.tmp` directory outside the store's monitor — a slow content
     * provider must not hold up pending(), delete() or another enqueue — and commits it by rename
     * under the monitor.
     */
    fun enqueue(payload: ReportPayload, attachments: List<NewAttachment>): StoredReport {
        val createdAt = clock()
        val name = "$createdAt-${payload.id}"
        val tmp = File(root, name + TMP_SUFFIX)
        synchronized(this) {
            root.mkdirs()
            inFlight += tmp.name
        }
        try {
            tmp.deleteRecursively()
            val attachmentsDir = File(tmp, ATTACHMENTS_DIR).apply { mkdirs() }
            val stored = attachments.mapNotNull { copyAttachment(it, attachmentsDir) }
            File(tmp, REPORT_FILE).writeText(ReportJson.encode(payload.copy(attachments = stored)))
            synchronized(this) {
                val committed = File(root, name)
                if (!tmp.renameTo(committed)) throw IOException("Cannot commit report ${payload.id}")
                enforceLimits(keep = committed)
                return StoredReport(payload.id, committed, createdAt, failed = false)
            }
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw e
        } finally {
            synchronized(this) { inFlight -= tmp.name }
        }
    }

    @Synchronized
    fun pending(): List<StoredReport> = committed().filterNot { it.failed }

    @Synchronized
    fun delete(id: String) {
        find(id)?.directory?.deleteRecursively()
    }

    @Synchronized
    fun markFailed(id: String) {
        find(id)?.let {
            try {
                File(it.directory, FAILED_MARKER).createNewFile()
            } catch (e: IOException) {
                logger.w("Could not mark report $id as failed", e)
            }
        }
    }

    /** Removes interrupted writes and enforces [limits]. Call once per process start. */
    @Synchronized
    fun cleanUp() {
        (root.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(TMP_SUFFIX) && it.name !in inFlight }
            .forEach { it.deleteRecursively() }
        enforceLimits()
    }

    private fun find(id: String): StoredReport? = committed().firstOrNull { it.id == id }

    private fun committed(): List<StoredReport> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.endsWith(TMP_SUFFIX) }
            .mapNotNull(::parse)
            .sortedWith(compareBy({ it.createdAtMillis }, { it.id }))

    private fun parse(dir: File): StoredReport? {
        val dash = dir.name.indexOf('-')
        if (dash <= 0) return null
        val createdAt = dir.name.substring(0, dash).toLongOrNull() ?: return null
        return StoredReport(dir.name.substring(dash + 1), dir, createdAt, File(dir, FAILED_MARKER).exists())
    }

    private fun copyAttachment(attachment: NewAttachment, dir: File): AttachmentMeta? {
        val name = attachment.meta.fileName
        val input = try {
            attachment.open()
        } catch (e: Exception) {
            logger.w("Attachment $name skipped: cannot open it", e)
            return null
        }
        if (input == null) {
            logger.w("Attachment $name skipped: its source is unavailable")
            return null
        }
        val target = File(dir, attachment.meta.id)
        return try {
            val size = input.use { copyLimited(it, target, attachment.maxBytes) }
            if (size == null) {
                target.delete()
                logger.w("Attachment $name skipped: larger than ${attachment.maxBytes} bytes")
                null
            } else {
                attachment.meta.copy(sizeBytes = size)
            }
        } catch (e: Exception) {
            target.delete()
            logger.w("Attachment $name skipped: copy failed", e)
            null
        }
    }

    /**
     * Drops the oldest committed reports until [limits] hold: first those past the age limit, then
     * by count, then by total bytes. Only committed reports count — an enqueue still writing its
     * `.tmp` directory is neither counted in the byte budget nor evicted — and [keep], the report
     * just committed, is never evicted: the user was told it is on its way. When [keep] alone is
     * over the byte budget it stays, with a warning. Called under the monitor.
     *
     * Eviction may delete a report the worker is sending right now; the send then fails with
     * FileNotFound and is retried — no other report is lost.
     */
    private fun enforceLimits(keep: File? = null) {
        val now = clock()
        committed()
            .filter { it.directory != keep && now - it.createdAtMillis > limits.maxAgeMillis }
            .forEach { evict(it, "older than ${limits.maxAgeMillis} ms") }

        val reports = committed().toMutableList()
        val evictable = reports.filterTo(ArrayDeque()) { it.directory != keep }
        while (reports.size > limits.maxReports && evictable.isNotEmpty()) {
            val oldest = evictable.removeFirst()
            evict(oldest, "the queue holds more than ${limits.maxReports} reports")
            reports -= oldest
        }

        var total = reports.sumOf { it.directory.sizeBytes() }
        while (total > limits.maxTotalBytes && evictable.isNotEmpty()) {
            val oldest = evictable.removeFirst()
            total -= oldest.directory.sizeBytes()
            evict(oldest, "the queue exceeds ${limits.maxTotalBytes} bytes")
        }
        if (total > limits.maxTotalBytes && keep != null) {
            logger.w("Report ${keep.name} alone exceeds the queue budget of ${limits.maxTotalBytes} bytes; it is kept")
        }
    }

    private fun evict(report: StoredReport, reason: String) {
        logger.w("Report ${report.id} dropped: $reason")
        report.directory.deleteRecursively()
    }

    private fun File.sizeBytes(): Long = walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val REPORT_FILE: String = "report.json"
        const val ATTACHMENTS_DIR: String = "attachments"
        const val FAILED_MARKER: String = "failed"
        const val TMP_SUFFIX: String = ".tmp"
    }
}

package io.github.feedbacklib.android.internal.queue

import io.github.feedbacklib.android.PreparedAttachment
import io.github.feedbacklib.android.PreparedReport
import io.github.feedbacklib.android.internal.report.ReportJson
import java.io.File

/** A committed report directory in the queue. */
internal data class StoredReport(
    val id: String,
    val directory: File,
    val createdAtMillis: Long,
    val failed: Boolean,
) {
    /** Reads `report.json`; throws if the directory is damaged. */
    fun toPrepared(): PreparedReport {
        val json = File(directory, ReportStore.REPORT_FILE).readText()
        val payload = ReportJson.decode(json)
        val attachmentsDir = File(directory, ReportStore.ATTACHMENTS_DIR)
        return PreparedReport(
            id = payload.id,
            cid = payload.cid,
            type = payload.type,
            reportJson = json,
            attachments = payload.attachments.map {
                PreparedAttachment(it.id, it.kind, it.fileName, it.mimeType, it.sizeBytes, File(attachmentsDir, it.id))
            },
        )
    }
}

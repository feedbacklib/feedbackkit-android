package io.github.feedbacklib.android

import java.io.File

internal fun preparedReport(dir: File, vararg files: Pair<String, ByteArray>): PreparedReport {
    val attachments = files.mapIndexed { index, (name, bytes) ->
        val file = File(dir, "att-$index").apply { writeBytes(bytes) }
        PreparedAttachment("att-$index", AttachmentKind.APP_FILE, name, "text/plain", bytes.size.toLong(), file)
    }
    return PreparedReport("report-1", "cid-1", ReportType.BUG, """{"id":"report-1"}""", attachments)
}

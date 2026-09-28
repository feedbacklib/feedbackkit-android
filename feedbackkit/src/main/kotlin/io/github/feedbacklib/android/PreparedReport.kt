package io.github.feedbacklib.android

import java.io.File

/** An immutable snapshot of a stored report, ready to be delivered. */
public class PreparedReport internal constructor(
    public val id: String,
    public val cid: String,
    public val type: ReportType,
    /** The report's metadata exactly as stored in `report.json`. */
    public val reportJson: String,
    public val attachments: List<PreparedAttachment>,
)

/** One attachment of a [PreparedReport]; [file] is readable until the report is delivered. */
public class PreparedAttachment internal constructor(
    public val id: String,
    public val kind: AttachmentKind,
    public val fileName: String,
    public val mimeType: String,
    public val sizeBytes: Long,
    public val file: File,
)

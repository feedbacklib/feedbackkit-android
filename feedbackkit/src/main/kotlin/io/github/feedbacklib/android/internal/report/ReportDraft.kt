package io.github.feedbacklib.android.internal.report

import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.ReportType
import java.io.File

/** What the report UI (stage 4) or proactive reporting (stage 7) hands to [ReportSubmitter]. */
internal data class ReportDraft(
    val type: ReportType,
    val email: String?,
    val comment: String,
    val extended: ExtendedFields? = null,
    val proactive: ProactiveInfo? = null,
    val attachments: List<DraftAttachment> = emptyList(),
    val currentScreen: String? = null,
)

internal data class DraftAttachment(
    val kind: AttachmentKind,
    val file: File,
    val fileName: String,
    val mimeType: String,
)

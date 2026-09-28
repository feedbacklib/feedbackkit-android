package io.github.feedbacklib.android.internal.core

import io.github.feedbacklib.android.ColorTheme
import io.github.feedbacklib.android.ExtendedBugReport
import io.github.feedbacklib.android.Option
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.TextKey

/** Everything the report screen reads from the host's configuration (spec §4, §6). */
internal data class ReportUiConfig(
    val reportTypes: Set<ReportType> = DEFAULT_REPORT_TYPES,
    val options: Set<Option> = emptySet(),
    val commentMinimums: Map<ReportType, Int> = emptyMap(),
    val extendedState: ExtendedBugReport.State = ExtendedBugReport.State.DISABLED,
    val extendedHints: ExtendedHints = ExtendedHints(),
    val colorTheme: ColorTheme = ColorTheme.SYSTEM,
    val primaryColor: Int? = null,
    val customTexts: Map<TextKey, String> = emptyMap(),
    val attachmentTypes: AttachmentTypes = AttachmentTypes(),
) {
    companion object {
        val DEFAULT_REPORT_TYPES: Set<ReportType> = setOf(ReportType.BUG, ReportType.FEEDBACK, ReportType.QUESTION)
    }
}

/** Placeholders of the extended bug report fields; `null` means the SDK's own text. */
internal data class ExtendedHints(
    val steps: String? = null,
    val actual: String? = null,
    val expected: String? = null,
)

/** Which attachments the report screen offers (spec §4 `setAttachmentTypesEnabled`); all on by default. */
internal data class AttachmentTypes(
    val initialScreenshot: Boolean = true,
    val extraScreenshot: Boolean = true,
    val gallery: Boolean = true,
    /** "Record screen" in the form; shown only when feedbackkit-recording provides a recorder. */
    val screenRecording: Boolean = true,
)

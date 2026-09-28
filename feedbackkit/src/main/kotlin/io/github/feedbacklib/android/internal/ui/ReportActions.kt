package io.github.feedbacklib.android.internal.ui

import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.report.DraftFile

/**
 * Everything the report screens can ask for; implemented by ReportDraftViewModel. Main thread.
 * Stages 5–6 add their own actions here (annotate, extra screenshot, gallery, recording).
 */
internal interface ReportActions : AnnotationActions {
    fun onTypeSelected(type: ReportType)
    fun onEmailChanged(value: String)
    fun onCommentChanged(value: String)
    fun onStepsChanged(value: String)
    fun onActualChanged(value: String)
    fun onExpectedChanged(value: String)
    fun onRemoveAttachment(file: DraftFile)
    fun onAddGalleryImage()

    /** "Add screenshot": the screen steps aside so the user can capture another screen (spec §6). */
    fun onAddExtraScreenshot()

    /** "Record screen": the consent dialog, then the report steps aside while the screen is recorded (spec §7). */
    fun onRecordScreen()

    /** "Tell us" on proactive reporting's prompt: its FRUSTRATING_EXPERIENCE form (spec §8). */
    fun onProactiveAccepted()

    /** "Not now" on the prompt, or a tap beside it: closed as a cancel. */
    fun onProactiveDeclined()

    /** Opens the annotation editor on [file] (a tap on its thumbnail). */
    fun onEditAttachment(file: DraftFile)

    fun onPrimaryAction()
    fun onBack()
    fun onCancelRequested()
    fun onCancelConfirmed()
    fun onCancelDismissed()
}

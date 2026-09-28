package io.github.feedbacklib.android.internal.ui

import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.ColorTheme
import io.github.feedbacklib.android.ExtendedBugReport
import io.github.feedbacklib.android.Option
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.TextKey
import io.github.feedbacklib.android.internal.core.ExtendedHints
import io.github.feedbacklib.android.internal.core.ReportUiConfig
import io.github.feedbacklib.android.internal.report.AddImageResult
import io.github.feedbacklib.android.internal.report.DraftAttachment
import io.github.feedbacklib.android.internal.report.DraftFile
import io.github.feedbacklib.android.internal.report.ExtendedFields
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.internal.report.ReportDraft

/** Where the user is in the report flow (spec §6). Saved by name: never rename an entry. */
internal enum class ReportStep { MENU, FORM, EXTENDED, SENDING, SUCCESS, ANNOTATE, PROACTIVE_PROMPT }

internal enum class EmailMode { REQUIRED, OPTIONAL, HIDDEN }

internal enum class PrimaryAction { NEXT, SEND }

internal fun interface EmailValidator {
    fun isValid(email: String): Boolean
}

/** Limits of the attachment strip (spec §6). */
internal object AttachmentRules {
    /** User attachments per report, the invocation screenshot included; host files do not count. */
    const val MAX_USER_ATTACHMENTS: Int = 4

    /** A gallery image larger than this is refused: the queue holds at most 100 MB over 20 reports. */
    const val GALLERY_MAX_BYTES: Long = 10L * 1024 * 1024
}

/** Why the last attachment action did not add anything; shown under the strip. Saved by name. */
internal enum class AttachNotice { SCREENSHOT_UNAVAILABLE, LIMIT_REACHED, IMAGE_TOO_LARGE, IMAGE_UNSUPPORTED, IMAGE_FAILED, RECORDING_FAILED }

internal fun AddImageResult.toNotice(): AttachNotice? = when (this) {
    is AddImageResult.Added -> null
    AddImageResult.LimitReached -> AttachNotice.LIMIT_REACHED
    AddImageResult.TooLarge -> AttachNotice.IMAGE_TOO_LARGE
    AddImageResult.Unsupported -> AttachNotice.IMAGE_UNSUPPORTED
    AddImageResult.Failed -> AttachNotice.IMAGE_FAILED
}

/** Form rules of one report type, derived from the host's configuration. */
internal data class FormRules(
    val emailMode: EmailMode,
    val commentRequired: Boolean,
    val commentMinLength: Int,
    val extendedState: ExtendedBugReport.State,
) {
    val hasExtendedStep: Boolean
        get() = extendedState != ExtendedBugReport.State.DISABLED

    val extendedRequired: Boolean
        get() = extendedState == ExtendedBugReport.State.ENABLED_WITH_REQUIRED_FIELDS

    companion object {
        fun from(ui: ReportUiConfig, type: ReportType): FormRules {
            val minimum = (ui.commentMinimums[type] ?: 0).coerceAtLeast(0)
            return FormRules(
                emailMode = when {
                    Option.EMAIL_FIELD_HIDDEN in ui.options -> EmailMode.HIDDEN
                    Option.EMAIL_FIELD_OPTIONAL in ui.options -> EmailMode.OPTIONAL
                    else -> EmailMode.REQUIRED
                },
                // A minimum length only makes sense for a comment that must be written.
                commentRequired = Option.COMMENT_FIELD_REQUIRED in ui.options || minimum > 0,
                commentMinLength = minimum,
                extendedState = if (type == ReportType.BUG) ui.extendedState else ExtendedBugReport.State.DISABLED,
            )
        }
    }
}

/** What the user typed. */
internal data class DraftFields(
    val email: String = "",
    val comment: String = "",
    val steps: String = "",
    val actual: String = "",
    val expected: String = "",
)

internal data class FormCheck(
    val emailValid: Boolean,
    val showEmailError: Boolean,
    val emailMissing: Boolean,
    val commentValid: Boolean,
    val commentMissing: Boolean,
    val commentLength: Int,
) {
    val canContinue: Boolean
        get() = emailValid && commentValid

    companion object {
        val NONE: FormCheck = FormCheck(
            emailValid = true,
            showEmailError = false,
            emailMissing = false,
            commentValid = true,
            commentMissing = false,
            commentLength = 0,
        )
    }
}

internal object FormValidation {

    /** Characters as a person counts them: surrounding whitespace ignored, an emoji counts once. */
    fun commentLength(comment: String): Int {
        val text = comment.trim()
        return text.codePointCount(0, text.length)
    }

    fun check(fields: DraftFields, rules: FormRules, emails: EmailValidator): FormCheck {
        val email = fields.email.trim()
        val wellFormed = email.isNotEmpty() && emails.isValid(email)
        val length = commentLength(fields.comment)
        val needed = if (rules.commentRequired) maxOf(rules.commentMinLength, 1) else 0
        return FormCheck(
            emailValid = when (rules.emailMode) {
                EmailMode.HIDDEN -> true
                EmailMode.OPTIONAL -> email.isEmpty() || wellFormed
                EmailMode.REQUIRED -> wellFormed
            },
            showEmailError = rules.emailMode != EmailMode.HIDDEN && email.isNotEmpty() && !wellFormed,
            emailMissing = rules.emailMode == EmailMode.REQUIRED && email.isEmpty(),
            commentValid = length >= needed,
            commentMissing = rules.commentRequired && length == 0,
            commentLength = length,
        )
    }

    fun extendedValid(fields: DraftFields, rules: FormRules): Boolean =
        !rules.extendedRequired || listOf(fields.steps, fields.actual, fields.expected).all { it.isNotBlank() }
}

internal data class StartPoint(val step: ReportStep, val type: ReportType?, val menuShown: Boolean)

internal sealed interface BackAction {
    data class GoTo(val step: ReportStep) : BackAction
    data object RequestCancel : BackAction
    data object Ignore : BackAction
}

/** Moves between steps (spec §6): menu → form → extended (BUG only) → sending → thanks. */
internal object ReportNavigation {

    fun menuTypes(ui: ReportUiConfig): List<ReportType> =
        ReportType.entries.filter { it != ReportType.FRUSTRATING_EXPERIENCE && it in ui.reportTypes }

    /**
     * Proactive reporting's prompt comes first, with its own type (spec §8); a type from
     * `BugReporting.show(type)` or a single offered type skips the menu.
     */
    fun start(presetType: ReportType?, menuTypes: List<ReportType>, proactive: Boolean = false): StartPoint = when {
        proactive -> StartPoint(ReportStep.PROACTIVE_PROMPT, ReportType.FRUSTRATING_EXPERIENCE, menuShown = false)
        presetType != null -> StartPoint(ReportStep.FORM, presetType, menuShown = false)
        menuTypes.size > 1 -> StartPoint(ReportStep.MENU, null, menuShown = true)
        else -> StartPoint(ReportStep.FORM, menuTypes.firstOrNull() ?: ReportType.BUG, menuShown = false)
    }

    /** The step a send starts from: back there after a failure or a send lost to process death. */
    fun stepBeforeSend(rules: FormRules): ReportStep = if (rules.hasExtendedStep) ReportStep.EXTENDED else ReportStep.FORM

    fun back(step: ReportStep, menuShown: Boolean): BackAction = when (step) {
        ReportStep.MENU -> BackAction.RequestCancel
        ReportStep.FORM -> if (menuShown) BackAction.GoTo(ReportStep.MENU) else BackAction.RequestCancel
        ReportStep.EXTENDED -> BackAction.GoTo(ReportStep.FORM)
        ReportStep.SENDING, ReportStep.SUCCESS -> BackAction.Ignore
        // The editor decides for itself: it asks before dropping unsaved steps (ReportDraftViewModel).
        ReportStep.ANNOTATE -> BackAction.Ignore
        ReportStep.PROACTIVE_PROMPT -> BackAction.RequestCancel
    }

    /** Only what the user typed counts; the invocation screenshot and an untouched prefill do not. */
    fun needsCancelConfirmation(fields: DraftFields, emailPrefill: String): Boolean =
        listOf(fields.comment, fields.steps, fields.actual, fields.expected).any { it.isNotBlank() } ||
            fields.email.trim() != emailPrefill.trim()

    fun dismissReportType(type: ReportType?, menuTypes: List<ReportType>): ReportType =
        type ?: menuTypes.firstOrNull() ?: ReportType.BUG
}

/** The draft as the ViewModel keeps it; everything but the attachments is saved in SavedStateHandle. */
internal data class DraftState(
    val step: ReportStep,
    val type: ReportType?,
    val menuShown: Boolean,
    val fields: DraftFields,
    val emailPrefill: String,
    val screenshotUnavailable: Boolean,
    val attachments: List<DraftFile> = emptyList(),
    val confirmingCancel: Boolean = false,
    val submitFailed: Boolean = false,
    val notice: AttachNotice? = null,
    /** The consent dialog is open for "Record screen"; not saved: a new process has no dialog. */
    val recordingStarting: Boolean = false,
    /**
     * Auto Screen Recording's clip is still being made (spec §7): a placeholder that holds its place
     * among the four. Saved as the clip's token, which the ViewModel keeps until the clip is resolved.
     */
    val autoClipPending: Boolean = false,
)

/** Everything the report screens draw. */
internal data class ReportUiState(
    val step: ReportStep,
    val type: ReportType?,
    val menuTypes: List<ReportType>,
    val canGoBack: Boolean,
    val fields: DraftFields,
    /** `null` on the menu, before a type is chosen. */
    val rules: FormRules?,
    val check: FormCheck,
    val extendedValid: Boolean,
    val primaryAction: PrimaryAction,
    val hints: ExtendedHints,
    val attachments: List<DraftFile>,
    val screenshotUnavailable: Boolean,
    val canAddGalleryImage: Boolean,
    val canAddExtraScreenshot: Boolean,
    val canRecordScreen: Boolean = false,
    val attachmentLimitReached: Boolean,
    val notice: AttachNotice?,
    val confirmingCancel: Boolean,
    val submitFailed: Boolean,
    val recordingStarting: Boolean = false,
    /** The automatic recording's placeholder at the end of the attachments. */
    val autoClipPending: Boolean = false,
    val colorTheme: ColorTheme,
    val primaryColor: Int?,
    val customTexts: Map<TextKey, String>,
    /** The annotation editor; set on [ReportStep.ANNOTATE] once it is open. */
    val annotation: AnnotationUiState? = null,
) {
    /** Whether the primary button works: the form check, and on the extended step its fields too. */
    val primaryEnabled: Boolean
        get() = when (step) {
            ReportStep.FORM -> check.canContinue
            ReportStep.EXTENDED -> check.canContinue && extendedValid
            ReportStep.MENU, ReportStep.SENDING, ReportStep.SUCCESS, ReportStep.ANNOTATE, ReportStep.PROACTIVE_PROMPT -> false
        }
}

internal fun DraftState.toUiState(ui: ReportUiConfig, emails: EmailValidator, canRecord: Boolean = false): ReportUiState {
    val rules = type?.let { FormRules.from(ui, it) }
    val types = ui.attachmentTypes
    // The automatic recording in preparation already holds its place (spec §7), unless the draft has it
    // already: the screen this one replaced took it in, and the token is about to resolve.
    val clipPending = autoClipPending && attachments.none { it.kind == AttachmentKind.AUTO_SCREEN_RECORDING }
    val room = attachments.size + (if (clipPending) 1 else 0) < AttachmentRules.MAX_USER_ATTACHMENTS
    // "Record screen" needs feedbackkit-recording (spec §3), the type switched on and room left.
    val recordingOffered = canRecord && types.screenRecording
    // The proactive report is about the previous run (spec §8): nothing on screen now shows it, so it
    // never steps aside for a screenshot or a recording; gallery images still can join it.
    val stepsAside = type != ReportType.FRUSTRATING_EXPERIENCE
    return ReportUiState(
        step = step,
        type = type,
        menuTypes = ReportNavigation.menuTypes(ui),
        canGoBack = ReportNavigation.back(step, menuShown) is BackAction.GoTo,
        fields = fields,
        rules = rules,
        check = rules?.let { FormValidation.check(fields, it, emails) } ?: FormCheck.NONE,
        extendedValid = rules?.let { FormValidation.extendedValid(fields, it) } ?: true,
        primaryAction = if (step == ReportStep.FORM && rules?.hasExtendedStep == true) PrimaryAction.NEXT else PrimaryAction.SEND,
        hints = ui.extendedHints,
        attachments = attachments,
        screenshotUnavailable = screenshotUnavailable,
        canAddGalleryImage = types.gallery && room,
        canAddExtraScreenshot = types.extraScreenshot && room && stepsAside,
        canRecordScreen = recordingOffered && room && stepsAside,
        attachmentLimitReached = !room && (types.gallery || (stepsAside && (types.extraScreenshot || recordingOffered))),
        notice = notice,
        confirmingCancel = confirmingCancel,
        submitFailed = submitFailed,
        recordingStarting = recordingStarting,
        autoClipPending = clipPending,
        colorTheme = ui.colorTheme,
        primaryColor = ui.primaryColor,
        customTexts = ui.customTexts,
    )
}

/**
 * The report handed to the submitter. [identifiedEmail] is what `identifyUser` set; it is sent when
 * the email field is hidden.
 */
internal fun DraftState.toReportDraft(
    type: ReportType,
    rules: FormRules,
    identifiedEmail: String?,
    files: List<DraftFile>,
    currentScreen: String?,
    proactive: ProactiveInfo? = null,
): ReportDraft = ReportDraft(
    type = type,
    email = when (rules.emailMode) {
        EmailMode.HIDDEN -> identifiedEmail?.trim()?.ifEmpty { null }
        else -> fields.email.trim().ifEmpty { null }
    },
    comment = fields.comment.trim(),
    extended = if (rules.hasExtendedStep) ExtendedFields(fields.steps.trim(), fields.actual.trim(), fields.expected.trim()) else null,
    attachments = files.map { DraftAttachment(it.kind, it.file, it.fileName, it.mimeType) },
    currentScreen = currentScreen,
    proactive = proactive,
)
